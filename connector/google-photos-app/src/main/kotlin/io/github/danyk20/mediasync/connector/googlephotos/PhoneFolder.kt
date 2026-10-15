/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** What the phone has at a stored item's location. */
internal sealed interface PhoneFile {
    /** There, in [folder] (a MediaStore relative path such as `DCIM/MediaSync/`), possibly in the trash. */
    data class Found(val folder: String, val trashed: Boolean) : PhoneFile {
        fun inFolder(path: String): Boolean = folder.trimEnd('/') == path.trimEnd('/')
    }

    /** No longer on the phone, not even in its trash. */
    data object Gone : PhoneFile

    /** MediaSync may not look, for example after a reinstall: nothing can be told from it. */
    data object Unreadable : PhoneFile
}

/** The received files on this phone, as the gallery sees them. */
internal interface PhoneFolder {
    /** What is at [location], a MediaStore content URI. */
    fun find(location: String): PhoneFile

    /** Emits when photos or videos on the phone may have changed or gone. */
    fun changes(): Flow<Unit>
}

@ExercisedOnDevice(coveredBy = "connector:google-photos-app MediaStoreFolderTest")
internal class MediaStoreFolder @Inject constructor(@ApplicationContext context: Context) : PhoneFolder {
    private val resolver: ContentResolver = context.contentResolver

    override fun find(location: String): PhoneFile = try {
        query(location)
    } catch (_: SecurityException) {
        PhoneFile.Unreadable
    } catch (_: IllegalArgumentException) {
        PhoneFile.Gone // Not a MediaStore item at all.
    }

    private fun query(location: String): PhoneFile {
        val uri = location.toUri()
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Trashed items are left out unless asked for: they must not look removed.
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            resolver.query(uri, arrayOf(PATH, MediaStore.MediaColumns.IS_TRASHED), args, null)
        } else {
            resolver.query(uri, arrayOf(PATH), null, null, null)
        }
        return cursor?.use {
            if (!it.moveToFirst()) return PhoneFile.Gone
            val trashed = it.columnCount > 1 && it.getInt(1) == 1
            PhoneFile.Found(it.getString(0).orEmpty(), trashed)
        } ?: PhoneFile.Unreadable
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        COLLECTIONS.forEach { resolver.registerContentObserver(it, true, observer) }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private companion object {
        const val PATH = MediaStore.MediaColumns.RELATIVE_PATH
        val COLLECTIONS = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
    }
}
