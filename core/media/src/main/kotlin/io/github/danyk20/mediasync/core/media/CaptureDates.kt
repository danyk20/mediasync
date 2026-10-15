/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import androidx.exifinterface.media.ExifInterface
import io.github.danyk20.mediasync.core.metadata.ByteReader
import io.github.danyk20.mediasync.core.metadata.CaptureDate
import io.github.danyk20.mediasync.core.metadata.CaptureDateGuess
import io.github.danyk20.mediasync.core.metadata.DateSource
import io.github.danyk20.mediasync.core.metadata.VideoDates
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Completes the capture date in received photos and videos (docs/PLAN.md §6.2), before they become visible to the
 * gallery and to the Google Photos app, which backs up the stored file itself (§9.3.1). Only metadata changes; image
 * and video data are copied byte for byte.
 *
 * - Files without a date, such as WhatsApp images and videos, get the best one there is: the sending phone's gallery
 *   date, else the date in the file's name, else when it was last changed (§6.2, point 3). Android's media scanner and
 *   Google Photos take the date only from the file, so they would land on the day they arrived.
 * - On request, photos whose date has no time zone get the producer's. Android estimates a missing zone from the
 *   file's modification time, which Android 10 can't carry over to a received file.
 */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
internal object CaptureDates {

    private val PHOTO_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private val VIDEO_TYPES = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2")
    private const val SECONDS_PER_MINUTE = 60

    /**
     * @param file a readable, writable, seekable descriptor of the complete file.
     * @param addTimeZone whether to add the zone to a photo's date that has none.
     * @param zone where local times in file names were taken, without a gallery date to tell: this phone's zone.
     * @return the capture date written into a file that had none, for the gallery; `null` if none was written.
     */
    fun complete(
        file: FileDescriptor,
        descriptor: MediaDescriptor,
        addTimeZone: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = when (descriptor.mimeType) {
        in PHOTO_TYPES -> completePhoto(file, descriptor, addTimeZone, zone)

        // Two channels on the one descriptor: one can't both read and write it.
        in VIDEO_TYPES ->
            StoredVideoDates.complete(FileInputStream(file).channel, FileOutputStream(file).channel, descriptor, zone)

        else -> null
    }

    private fun completePhoto(
        file: FileDescriptor,
        descriptor: MediaDescriptor,
        addTimeZone: Boolean,
        zone: ZoneId,
    ): Long? {
        val galleryDate = descriptor.meta.capturedAtMillis
        // One reader for both checks: readers of the same descriptor share its file position.
        val exif = ExifInterface(file)
        val local = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if (local == null) {
            return guess(descriptor, zone)?.let { date ->
                writeDate(exif, date.epochMillis, offsetOf(date, descriptor, zone))
                exif.saveAttributes()
                date.epochMillis
            }
        }
        if (addTimeZone && galleryDate != null && exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) == null) {
            captureOffset(local, galleryDate)?.let { offset ->
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset.exifText())
                exif.saveAttributes()
            }
        }
        return null
    }

    private fun writeDate(exif: ExifInterface, capturedAtMillis: Long, offset: ZoneOffset) {
        val date = Instant.ofEpochMilli(capturedAtMillis).atOffset(offset).format(EXIF_DATE)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset.exifText())
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset.exifText())
    }

    /** The producer's offset for a gallery date, as it said; for other dates, this phone's zone's at that moment. */
    private fun offsetOf(date: CaptureDate, descriptor: MediaDescriptor, zone: ZoneId): ZoneOffset {
        val known = descriptor.meta.utcOffsetMinutes.takeIf { date.source == DateSource.GALLERY }
        return known?.let { ZoneOffset.ofTotalSeconds(it * SECONDS_PER_MINUTE) }
            ?: zone.rules.getOffset(Instant.ofEpochMilli(date.epochMillis))
    }
}

/**
 * Writes a capture date into a received video that has none (docs/PLAN.md §6.2, point 3): the movie header's creation
 * time, in place, so the file keeps its size and its samples.
 */
internal object StoredVideoDates {
    /** Reads the video through [read] and edits it through [write], both on the same file. The date written, if any. */
    fun complete(read: FileChannel, write: FileChannel, descriptor: MediaDescriptor, zone: ZoneId): Long? {
        val size = read.size()
        val reader = ByteReader { offset, count ->
            ByteArray(count).also { bytes ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    val n = read.read(buffer, offset + buffer.position())
                    if (n < 0) throw IndexOutOfBoundsException("Read past the end")
                }
            }
        }
        val date = guess(descriptor, zone) ?: return null
        // None for a video that has its own capture time.
        val edits = VideoDates.dateEdits(size, reader, date.epochMillis)
        edits.forEach { edit ->
            val buffer = ByteBuffer.wrap(edit.bytes)
            while (buffer.hasRemaining()) write.write(buffer, edit.offset + buffer.position())
        }
        if (edits.isNotEmpty()) write.force(true)
        return date.epochMillis.takeIf { edits.isNotEmpty() }
    }
}

private fun guess(descriptor: MediaDescriptor, zone: ZoneId): CaptureDate? {
    val meta = descriptor.meta
    return CaptureDateGuess.resolve(meta.capturedAtMillis, descriptor.displayName, meta.modifiedAtMillis, zone)
}

private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
private const val QUARTER_HOUR_SECONDS = 15 * 60L
private const val MAX_OFFSET_SECONDS = 14 * 60 * 60L

/**
 * The UTC offset at which the EXIF local time [localDateTime] is the moment [capturedAtMillis], rounded to the
 * quarter hours all time zones are made of. `null` if the two can't be the same moment.
 */
internal fun captureOffset(localDateTime: String, capturedAtMillis: Long): ZoneOffset? {
    val local = try {
        LocalDateTime.parse(localDateTime.trim(), EXIF_DATE)
    } catch (_: DateTimeParseException) {
        return null
    }
    val seconds = Duration.between(Instant.ofEpochMilli(capturedAtMillis), local.toInstant(ZoneOffset.UTC)).seconds
    val offset = (seconds.toDouble() / QUARTER_HOUR_SECONDS).roundToLong() * QUARTER_HOUR_SECONDS
    return if (abs(offset) > MAX_OFFSET_SECONDS) null else ZoneOffset.ofTotalSeconds(offset.toInt())
}

/** EXIF writes UTC as `+00:00`, not `Z`. */
private fun ZoneOffset.exifText(): String = if (this == ZoneOffset.UTC) "+00:00" else id
