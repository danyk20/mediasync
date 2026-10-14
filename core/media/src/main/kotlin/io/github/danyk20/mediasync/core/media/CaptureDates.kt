/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import androidx.exifinterface.media.ExifInterface
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import java.io.FileDescriptor
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Completes the capture date in received photos (docs/PLAN.md §6.2). Only the EXIF block changes; image
 * data is copied byte for byte.
 *
 * - Photos without a date, such as WhatsApp images, get the producer's, with its time zone. Android's media
 *   scanner and Google Photos take the date only from the file, so they would land on the day they arrived.
 * - On request, photos whose date has no time zone get the producer's. Android estimates a missing zone from
 *   the file's modification time, which Android 10 can't carry over to a received file.
 */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
internal object CaptureDates {

    private val WRITABLE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private const val SECONDS_PER_MINUTE = 60
    private const val MILLIS_PER_MINUTE = 60_000

    /**
     * @param file a readable, writable, seekable descriptor of the complete file.
     * @param utcOffsetMinutes the producer's offset at capture time; this device's zone is used if unknown.
     * @param addTimeZone whether to add the zone to a date that has none.
     * @return whether anything was written.
     */
    fun complete(
        file: FileDescriptor,
        mimeType: String,
        capturedAtMillis: Long?,
        utcOffsetMinutes: Int?,
        addTimeZone: Boolean,
    ): Boolean {
        if (capturedAtMillis == null || mimeType !in WRITABLE_TYPES) return false
        // One reader for both checks: readers of the same descriptor share its file position.
        val exif = ExifInterface(file)
        val local = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        when {
            local == null -> writeDate(exif, capturedAtMillis, utcOffsetMinutes)

            addTimeZone && exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) == null -> {
                val offset = captureOffset(local, capturedAtMillis) ?: return false
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset.exifText())
            }

            else -> return false
        }
        exif.saveAttributes()
        return true
    }

    private fun writeDate(exif: ExifInterface, capturedAtMillis: Long, utcOffsetMinutes: Int?) {
        val offsetMinutes = utcOffsetMinutes ?: (TimeZone.getDefault().getOffset(capturedAtMillis) / MILLIS_PER_MINUTE)
        val offset = ZoneOffset.ofTotalSeconds(offsetMinutes * SECONDS_PER_MINUTE)
        val date = Instant.ofEpochMilli(capturedAtMillis).atOffset(offset).format(EXIF_DATE)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset.exifText())
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset.exifText())
    }
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
