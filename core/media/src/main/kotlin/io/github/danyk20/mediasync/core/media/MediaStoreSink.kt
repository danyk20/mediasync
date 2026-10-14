/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.PartialTransferDao
import io.github.danyk20.mediasync.core.database.PartialTransferEntity
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.sync.engine.IncomingFile
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaSink
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import okio.ByteString
import okio.Source
import okio.source

/**
 * Stores received photos and videos in `DCIM/MediaSync/`, the one folder the Google Photos app backs up
 * (docs/PLAN.md §9.3.1). `DCIM` is the only top-level folder that accepts both photos and videos on
 * Android 10.
 *
 * Files are written as MediaStore *pending* items: invisible to the gallery and to Google Photos until
 * they're complete and verified. An interrupted file stays pending, so the next session can resume it.
 */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
@Singleton
class MediaStoreSink internal constructor(
    context: Context,
    private val partials: PartialTransferDao,
    private val reserveBytes: Long,
) : MediaSink {

    @Inject
    constructor(@ApplicationContext context: Context, partials: PartialTransferDao) :
        this(context, partials, DEFAULT_RESERVE_BYTES)

    private val resolver: ContentResolver = context.contentResolver

    override fun availableBytes(): Long =
        (StatFs(Environment.getExternalStorageDirectory().path).availableBytes - reserveBytes).coerceAtLeast(0)

    override fun partialSize(producer: DeviceId, fingerprint: ByteString): Long {
        val partial = partials.get(producer.hex, fingerprint.hex()) ?: return 0
        val size = sizeOf(partial.location.toUri())
        if (size == null) partials.delete(producer.hex, fingerprint.hex()) // MediaStore expired it.
        return size ?: 0
    }

    override fun open(producer: DeviceId, descriptor: MediaDescriptor, offset: Long): IncomingFile {
        val fingerprint = descriptor.fingerprint.hex()
        val existing = partials.get(producer.hex, fingerprint)
        val uri = if (existing != null && offset > 0) {
            existing.location.toUri()
        } else {
            existing?.let { removeQuietly(it.location.toUri()) }
            insertPending(descriptor).also { uri ->
                partials.upsert(
                    PartialTransferEntity(producer.hex, fingerprint, uri.toString(), System.currentTimeMillis()),
                )
            }
        }
        val file = resolver.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException("Can't open $uri")
        return PendingFile(producer, fingerprint, uri, descriptor, file, offset)
    }

    private fun insertPending(descriptor: MediaDescriptor): Uri {
        val collection = if (descriptor.mimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, descriptor.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, descriptor.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            putGalleryDetails(descriptor)
        }
        return resolver.insert(collection, values) ?: throw IOException("MediaStore refused ${descriptor.displayName}")
    }

    /** Capture date and favourite from the producer's gallery (docs/PLAN.md §6.1). */
    private fun ContentValues.putGalleryDetails(descriptor: MediaDescriptor) {
        descriptor.meta.capturedAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && descriptor.meta.favorite) {
            put(MediaStore.MediaColumns.IS_FAVORITE, 1)
        }
    }

    private fun sizeOf(uri: Uri): Long? = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun removeQuietly(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: SecurityException) {
            // Not ours any more (e.g. after a reinstall); MediaStore expires abandoned pending items itself.
        }
    }

    private inner class PendingFile(
        private val producer: DeviceId,
        private val fingerprint: String,
        private val uri: Uri,
        private val descriptor: MediaDescriptor,
        private val file: ParcelFileDescriptor,
        offset: Long,
    ) : IncomingFile {
        private val channel: FileChannel = FileOutputStream(file.fileDescriptor).channel.apply {
            truncate(offset)
            position(offset)
        }
        private var open = true

        override fun existingBytes(): Source =
            resolver.openInputStream(uri)?.source() ?: throw FileNotFoundException("Can't read $uri")

        override fun write(bytes: ByteArray, offset: Int, byteCount: Int) {
            val buffer = ByteBuffer.wrap(bytes, offset, byteCount)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun commit(): String {
            channel.force(true)
            close()
            val meta = descriptor.meta
            resolver.openFileDescriptor(uri, "rw")?.use { complete ->
                CaptureDates.complete(
                    complete.fileDescriptor,
                    descriptor.mimeType,
                    meta.capturedAtMillis,
                    meta.utcOffsetMinutes,
                    // Android 11+ keeps the file byte for byte, and its modification time, instead.
                    addTimeZone = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
                )
            }
            meta.modifiedAtMillis?.let(::keepModifiedTime)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                putGalleryDetails(descriptor)
            }
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Couldn't publish $uri")
            partials.delete(producer.hex, fingerprint)
            return uri.toString()
        }

        /**
         * Gives the stored file the producer's modification time, as the file's own metadata. Most photos
         * record their capture time without a time zone, and Android's media scanner estimates the zone from
         * the modification time; a file that looked modified on arrival would shift by the delay
         * (docs/PLAN.md §6.2). Android 10 gives apps no file paths; there the EXIF gets the time zone instead.
         */
        private fun keepModifiedTime(modifiedAtMillis: Long) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            @Suppress("DEPRECATION") // The path of a file this app owns, which Android 11+ lets it use.
            val column = MediaStore.MediaColumns.DATA
            val path = resolver.query(uri, arrayOf(column), null, null, null)?.use { row ->
                if (row.moveToFirst()) row.getString(0) else null
            }
            path?.let { File(it).setLastModified(modifiedAtMillis) }
        }

        override fun discard() {
            close()
            removeQuietly(uri)
            partials.delete(producer.hex, fingerprint)
        }

        override fun close() {
            if (!open) return
            open = false
            channel.close()
            file.close()
        }
    }

    companion object {
        const val FOLDER = "DCIM/MediaSync"

        /** Receiving stops while less than this would remain free (docs/PLAN.md §2.2). */
        const val DEFAULT_RESERVE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
