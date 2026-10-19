/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The names received photos and videos get (docs/PLAN.md §9.3.1). With *Simple file names* on, as at first, a name
 * with more than one dot keeps only the last: the Google Photos app doesn't show files such as Samsung's
 * `20251018_101530.LS.mp4`, so it becomes `20251018_101530_LS.mp4`.
 */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
@Singleton
class ReceivedNames internal constructor(context: Context, private val simple: suspend () -> Boolean) {

    @Inject
    constructor(@ApplicationContext context: Context, settings: SettingsRepository) :
        this(context, { settings.simpleFileNames.first() })

    private val resolver: ContentResolver = context.contentResolver

    /** The name a file sent as [original] is stored under. Called on I/O threads. */
    fun of(original: String): String = if (runBlocking { simple() }) simpleFileName(original) else original

    /**
     * Gives what this phone received before under a name with more than one dot the simple one, if *Simple file
     * names* is on. Only files MediaSync stored can be renamed; others, such as those from before a reinstall, keep
     * their name. The number renamed.
     */
    suspend fun simplifyStored(): Int {
        if (!simple()) return 0
        return COLLECTIONS.sumOf(::simplifyIn)
    }

    private fun simplifyIn(collection: Uri): Int {
        val found = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("${MediaStoreSink.FOLDER}/", "%.%.%"), // More than one dot.
            null,
        )?.use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getLong(0) to rows.getString(1)) }
        }.orEmpty()
        return found.count { (id, name) -> rename(ContentUris.withAppendedId(collection, id), simpleFileName(name)) }
    }

    @Suppress("SwallowedException") // A file that can't be renamed keeps its name; nothing else depends on it.
    private fun rename(uri: Uri, name: String): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
        resolver.update(uri, values, null, null) == 1
    } catch (e: SecurityException) {
        false // Not MediaSync's file any more, e.g. after a reinstall.
    } catch (e: IllegalStateException) {
        false // MediaStore couldn't move the file.
    } catch (e: IllegalArgumentException) {
        false
    }

    private companion object {
        val COLLECTIONS = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
    }
}

/**
 * [name] with only its last dot, before the extension; any others become `_`. A name with one dot, or none, stays
 * as it is; so does one whose only dot starts it.
 */
internal fun simpleFileName(name: String): String {
    val last = name.lastIndexOf('.')
    if (last <= 0 || name.indexOf('.') == last) return name
    return name.substring(0, last).replace('.', '_') + name.substring(last)
}
