/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.time.ZoneId

/** A capture time found inside a file; the offset is the camera's if [offsetKnown], else the reader's zone's. */
data class CapturedAt(val epochMillis: Long, val utcOffsetMinutes: Int, val offsetKnown: Boolean)

/**
 * The capture time written inside a photo (EXIF, in JPEG, HEIC, AVIF and TIFF-based RAW files) or a video (the movie
 * header's creation time), for a source that has no gallery to ask, such as a computer's folder (docs/PLAN.md §6.2).
 */
object EmbeddedDates {

    /** `null` if the file has no readable date, is of another format, or is damaged. */
    @Suppress("TooGenericExceptionCaught") // Reading untrusted bytes: a bounds error means a damaged file.
    fun capturedAt(size: Long, reader: ByteReader, zone: ZoneId): CapturedAt? {
        if (size < SNIFF_BYTES) return null
        return try {
            read(size, reader, zone)
        } catch (e: RuntimeException) {
            if (UNREADABLE.any { it.isInstance(e) }) null else throw e
        }
    }

    private fun read(size: Long, reader: ByteReader, zone: ZoneId): CapturedAt? =
        when (Format.of(reader.read(0, SNIFF_BYTES.toInt()))) {
            Format.JPEG -> Jpeg(reader, 0, size).exifTiff()?.capturedAt(zone)
            Format.TIFF -> Tiff(reader, 0, size).capturedAt(zone)
            Format.ISOBMFF -> isobmff(size, reader, zone)
            else -> null
        }

    private fun isobmff(size: Long, reader: ByteReader, zone: ZoneId): CapturedAt? {
        val file = Isobmff(reader, 0, size)
        return when (file.kind) {
            Isobmff.Kind.HEIF -> file.exifTiff()?.capturedAt(zone)
            Isobmff.Kind.VIDEO -> video(size, reader, zone)
            Isobmff.Kind.RAW -> null
        }
    }

    private fun video(size: Long, reader: ByteReader, zone: ZoneId): CapturedAt? =
        VideoDates.capturedAtMillis(size, reader)?.let { millis ->
            val offset = zone.rules.getOffset(java.time.Instant.ofEpochMilli(millis))
            CapturedAt(millis, offset.totalSeconds / SECONDS_PER_MINUTE, offsetKnown = false)
        }

    private val UNREADABLE = listOf(
        MalformedMediaException::class.java,
        IllegalArgumentException::class.java,
        IndexOutOfBoundsException::class.java,
        java.nio.BufferUnderflowException::class.java,
    )
    private const val SNIFF_BYTES = 16L
    private const val SECONDS_PER_MINUTE = 60
}
