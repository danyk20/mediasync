/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import androidx.exifinterface.media.ExifInterface
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import java.io.FileDescriptor
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Writes the producer's capture date into photos that carry none, such as WhatsApp images
 * (docs/PLAN.md §6.2).
 *
 * Android's media scanner and Google Photos only take the date from the file itself, so without it these
 * photos would land on the day they were received. Only the EXIF block is added; image data is copied
 * byte for byte. Files that already have a capture date are never changed.
 */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
internal object CaptureDates {

    private val WRITABLE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private const val SECONDS_PER_MINUTE = 60
    private const val MILLIS_PER_MINUTE = 60_000

    /**
     * @param file a readable, writable, seekable descriptor of the complete file.
     * @param utcOffsetMinutes the producer's offset at capture time; this device's zone is used if unknown.
     * @return whether a date was written.
     */
    fun addIfMissing(file: FileDescriptor, mimeType: String, capturedAtMillis: Long?, utcOffsetMinutes: Int?): Boolean {
        if (capturedAtMillis == null || mimeType !in WRITABLE_TYPES) return false
        val exif = ExifInterface(file)
        if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null) return false

        val offsetMinutes = utcOffsetMinutes ?: (TimeZone.getDefault().getOffset(capturedAtMillis) / MILLIS_PER_MINUTE)
        val offset = ZoneOffset.ofTotalSeconds(offsetMinutes * SECONDS_PER_MINUTE)
        val local = Instant.ofEpochMilli(capturedAtMillis).atOffset(offset)
        val date = local.format(EXIF_DATE)
        val zone = offset.id.let { if (it == "Z") "+00:00" else it }
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, zone)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, zone)
        exif.saveAttributes()
        return true
    }
}
