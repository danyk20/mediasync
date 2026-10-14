/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.MediaFingerprintDao
import io.github.danyk20.mediasync.core.database.MediaFingerprintEntity
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.sync.engine.Fingerprints
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.SourceItem
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.Source
import okio.source

/**
 * The producer's photos and videos, read from MediaStore: those in the folders the user chose to send
 * (docs/PLAN.md §2.2), read again before every listing so a new choice applies right away.
 *
 * Files are read as originals whenever the user granted `ACCESS_MEDIA_LOCATION`. Otherwise Android strips
 * the location from what the app can read (docs/PLAN.md §6.2).
 */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
@Singleton
class MediaStoreSource internal constructor(
    private val context: Context,
    private val fingerprints: MediaFingerprintDao,
    private val selection: suspend () -> SourceSelection,
) : MediaSource {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        fingerprints: MediaFingerprintDao,
        settings: SettingsRepository,
    ) : this(context, fingerprints, { settings.sourceSelection.first() })

    private val resolver: ContentResolver = context.contentResolver

    /** Whether Android will hand over unredacted location metadata. */
    fun canReadLocation(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Called on I/O threads, which may wait for the stored choice. */
    override fun items(): List<SourceItem> {
        val chosen = runBlocking { selection() }
        val original = canReadLocation()
        return COLLECTIONS.flatMap { collection ->
            query(collection) { row ->
                row.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
                    ?.takeIf(chosen::includes)
                    ?.let { toItem(collection, row, original) }
            }
        }
    }

    override fun open(item: SourceItem): Source =
        resolver.openInputStream(item.key.toUri())?.source() ?: throw FileNotFoundException(item.key)

    private fun <T : Any> query(collection: Uri, map: (Cursor) -> T?): List<T> {
        val cursor = resolver.query(
            collection,
            PROJECTION,
            "${MediaStore.MediaColumns.IS_PENDING} = 0",
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        ) ?: return emptyList()
        return cursor.use { generateSequence { if (it.moveToNext()) it else null }.mapNotNull(map).toList() }
    }

    private fun toItem(collection: Uri, row: Cursor, original: Boolean): SourceItem? {
        val uri = ContentUris.withAppendedId(collection, row.long(MediaStore.MediaColumns._ID))
        val readUri = if (original) MediaStore.setRequireOriginal(uri) else uri
        val mediaStoreSize = row.long(MediaStore.MediaColumns.SIZE)
        val modifiedAtMillis = TimeUnit.SECONDS.toMillis(row.long(MediaStore.MediaColumns.DATE_MODIFIED))
        val (fingerprint, byteCount) = fingerprintOf(readUri, mediaStoreSize, modifiedAtMillis, original) ?: return null
        val capturedAt = row.longOrNull(MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 }
        val meta = MediaMeta(
            capturedAtMillis = capturedAt,
            utcOffsetMinutes = capturedAt?.let { TimeZone.getDefault().getOffset(it) / MILLIS_PER_MINUTE },
            album = row.stringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
            favorite = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                row.longOrNull(MediaStore.MediaColumns.IS_FAVORITE) == 1L,
            modifiedAtMillis = modifiedAtMillis,
        )
        val descriptor = MediaDescriptor(
            fingerprint = fingerprint,
            size = byteCount,
            mimeType = row.stringOrNull(MediaStore.MediaColumns.MIME_TYPE) ?: "application/octet-stream",
            displayName = row.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME) ?: "${uri.lastPathSegment}",
            meta = meta,
        )
        return SourceItem(readUri.toString(), descriptor)
    }

    /** Cached when unchanged; otherwise reads the two samples. `null` if the file can't be read (skip it). */
    private fun fingerprintOf(
        uri: Uri,
        mediaStoreSize: Long,
        modifiedAtMillis: Long,
        original: Boolean,
    ): Pair<ByteString, Long>? {
        val key = uri.toString()
        fingerprints.get(key)
            ?.takeIf {
                it.mediaStoreSize == mediaStoreSize && it.modifiedAtMillis == modifiedAtMillis &&
                    it.original == original
            }
            ?.let { return it.fingerprint.decodeHex() to it.byteCount }
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val byteCount = descriptor.statSize
                    val fingerprint = Fingerprints.of(byteCount) { offset, count ->
                        ByteArray(count).also { bytes -> channel.readFully(ByteBuffer.wrap(bytes), offset) }
                    }
                    fingerprints.upsert(
                        MediaFingerprintEntity(
                            key,
                            mediaStoreSize,
                            modifiedAtMillis,
                            byteCount,
                            original,
                            fingerprint.hex(),
                        ),
                    )
                    fingerprint to byteCount
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000

        val COLLECTIONS = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )

        val PROJECTION = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()
    }
}

private fun java.nio.channels.FileChannel.readFully(buffer: ByteBuffer, position: Long) {
    var offset = position
    while (buffer.hasRemaining()) {
        val n = read(buffer, offset)
        if (n == -1) throw IOException("File shorter than expected")
        offset += n
    }
}

private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index == -1 || isNull(index)) null else getLong(index)
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index == -1 || isNull(index)) null else getString(index)
}
