/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.media.SharedItems
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** How queueing shared photos and videos went. */
data class Queued(val items: Int, val unreadable: Int = 0)

/**
 * Queues photos and videos shared to MediaSync, to send now (docs/PLAN.md, M6): gallery items as they are, other
 * files as copies. Must run while the share's read permission lasts, that is, while the share screen is open.
 */
class ShareQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shared: SharedItems,
    private val scheduler: SyncScheduler,
) {
    private val resolver: ContentResolver = context.contentResolver

    /** Queues [uris] and starts a sync. [progress] hears how many are done. Blocking: call it off the main thread. */
    fun queue(uris: List<Uri>, progress: (done: Int) -> Unit = {}): Queued {
        val locations = mutableListOf<String>()
        uris.forEachIndexed { index, uri ->
            (galleryItem(uri) ?: copy(uri))?.let(locations::add)
            progress(index + 1)
        }
        shared.add(locations)
        if (locations.isNotEmpty()) scheduler.syncNow()
        Diagnostics.log(Area.SEND, "Shared to send now: ${locations.size} of ${uris.size}")
        return Queued(locations.size, unreadable = uris.size - locations.size)
    }

    /** The gallery item behind [uri], if MediaSync may read it: it reads the gallery itself later. */
    private fun galleryItem(uri: Uri): String? {
        val gallery = galleryUriOf(uri) ?: documentGalleryUri(uri) ?: return null
        val readable = try {
            resolver.query(gallery, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
        } catch (_: SecurityException) {
            false
        }
        return gallery.toString().takeIf { readable == true }
    }

    /** A document from the Files app that is a gallery item (Android 11+). */
    private fun documentGalleryUri(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !DocumentsContract.isDocumentUri(context, uri)) return null
        return try {
            MediaStore.getMediaUri(context, uri)?.let(::galleryUriOf)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Copies a file that isn't in the gallery, under its own name; `null` if it can't be read. */
    private fun copy(uri: Uri): String? {
        val folder = File(shared.copies, UUID.randomUUID().toString())
        return try {
            folder.mkdirs()
            val file = File(folder, nameOf(uri))
            val input = resolver.openInputStream(uri) ?: throw IOException("Nothing to read")
            input.use { from -> file.outputStream().use { from.copyTo(it) } }
            Uri.fromFile(file).toString()
        } catch (e: IOException) {
            Diagnostics.log(Area.SEND, "A shared file couldn't be read", e)
            folder.deleteRecursively()
            null
        } catch (e: SecurityException) {
            Diagnostics.log(Area.SEND, "A shared file couldn't be read", e)
            folder.deleteRecursively()
            null
        }
    }

    /** The file's name as the sharing app gives it, safe as a file name; made up from its type otherwise. */
    private fun nameOf(uri: Uri): String {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        } catch (_: SecurityException) {
            null
        }
        val safe = name?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        if (safe != null) return safe
        val extension = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return "shared" + (extension?.let { ".$it" } ?: "")
    }
}
