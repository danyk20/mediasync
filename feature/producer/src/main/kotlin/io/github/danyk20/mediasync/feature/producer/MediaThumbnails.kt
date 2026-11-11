/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

/**
 * Thumbnails of photos and videos on this phone, for the waiting screen and the sending animation. Those already read
 * are kept, so scrolling back or sending again doesn't read them again.
 */
internal object MediaThumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / MEMORY_SHARE).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** The thumbnail of [uri] if it was read already. */
    fun cached(uri: String): Bitmap? = cache.get(uri)

    /**
     * At most [THUMBNAIL_PX] square; `null` if it can't be read: gone, or no longer allowed. Blocks: off the UI
     * thread.
     */
    fun load(context: Context, uri: String, video: Boolean): Bitmap? {
        cache.get(uri)?.let { return it }
        val parsed = uri.toUri()
        val size = Size(THUMBNAIL_PX, THUMBNAIL_PX)
        val bitmap = try {
            when {
                // A shared file's copy isn't in the gallery: read from the file itself.
                parsed.scheme == ContentResolver.SCHEME_FILE && video ->
                    ThumbnailUtils.createVideoThumbnail(File(parsed.path!!), size, null)

                parsed.scheme == ContentResolver.SCHEME_FILE ->
                    ThumbnailUtils.createImageThumbnail(File(parsed.path!!), size, null)

                else -> context.contentResolver.loadThumbnail(parsed, size, null)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return null
        cache.put(uri, bitmap)
        return bitmap
    }

    /**
     * Where a source item's thumbnail is: its key without the query that asks for the original file (with its
     * location), which thumbnails don't need.
     */
    fun uriOf(key: String): String = key.toUri().buildUpon().clearQuery().build().toString()

    /** Up to an eighth of the app's memory. */
    private const val MEMORY_SHARE = 8
    const val THUMBNAIL_PX = 256
}
