/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.core.model.SourceSelection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** A folder with photos or videos, as the sending phone's folder list shows it. */
data class MediaFolder(
    /** The MediaStore relative path, such as `DCIM/Camera/`. */
    val path: String,
    val itemCount: Int,
    val byteCount: Long,
    /** The newest item, for the folder's thumbnail. */
    val coverUri: String?,
) {
    val name: String get() = SourceSelection.nameOf(path)
}

/** This phone's photo and video library, by folder. */
interface MediaLibrary {
    /** Every folder with photos or videos, fullest first. */
    suspend fun folders(): List<MediaFolder>

    /** Emits whenever photos or videos are added, changed or removed. */
    fun changes(): Flow<Unit>
}

@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
@Singleton
class MediaStoreLibrary @Inject constructor(@ApplicationContext context: Context) : MediaLibrary {
    private val resolver = context.contentResolver

    override suspend fun folders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        val folders = HashMap<String, Tally>()
        for (collection in COLLECTIONS) {
            resolver.query(
                collection,
                PROJECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
                null,
                null,
            )?.use { row ->
                val id = row.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val path = row.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val size = row.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modified = row.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (row.moveToNext()) {
                    val folder = row.getString(path) ?: continue
                    folders.getOrPut(
                        folder,
                        ::Tally,
                    ).add(collection, row.getLong(id), row.getLong(size), row.getLong(modified))
                }
            }
        }
        folders.map { (path, tally) -> MediaFolder(path, tally.items, tally.bytes, tally.cover?.toString()) }
            .sortedWith(compareByDescending<MediaFolder> { it.itemCount }.thenBy { it.path })
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

    private class Tally {
        var items = 0
        var bytes = 0L
        var cover: Uri? = null
        private var coverModified = Long.MIN_VALUE

        fun add(collection: Uri, id: Long, size: Long, modified: Long) {
            items++
            bytes += size
            if (modified > coverModified) {
                coverModified = modified
                cover = ContentUris.withAppendedId(collection, id)
            }
        }
    }

    private companion object {
        val COLLECTIONS = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaModule {
    @Binds
    abstract fun bindMediaLibrary(impl: MediaStoreLibrary): MediaLibrary
}
