/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Where a capture date for a file without one came from, best first (docs/PLAN.md §6.2, point 3). */
enum class DateSource {
    /** The sending phone's gallery. */
    GALLERY,

    /** The file's name, such as `IMG-20250512-WA0003.jpg`. */
    FILE_NAME,

    /** When the file was last changed on the sending phone. */
    MODIFIED,
}

data class CaptureDate(val epochMillis: Long, val source: DateSource)

/**
 * Finds a capture date for a photo or video that has none inside (docs/PLAN.md §6.2, point 3): WhatsApp images,
 * downloads, some screenshots. Without one, Google Photos would file them under the day they arrived. The date inside
 * the file, when there is one, always wins: callers ask only when there's none.
 */
object CaptureDateGuess {

    /**
     * The gallery's date ([galleryMillis]), else the date in [fileName], else [modifiedAtMillis]. [zone] is where local
     * times in names were taken; the sending phone's zone isn't known without a gallery date, so callers pass their
     * own.
     */
    fun resolve(galleryMillis: Long?, fileName: String, modifiedAtMillis: Long?, zone: ZoneId): CaptureDate? = when {
        galleryMillis != null && galleryMillis > 0 -> CaptureDate(galleryMillis, DateSource.GALLERY)

        else -> fromName(fileName, modifiedAtMillis, zone)?.let { CaptureDate(it, DateSource.FILE_NAME) }
            ?: modifiedAtMillis?.takeIf { it > 0 }?.let { CaptureDate(it, DateSource.MODIFIED) }
    }

    /**
     * The date in a camera's or app's file name, or `null`:
     * - date and time: `PXL_20250512_103045123` (Pixel: UTC), `IMG_20250512_103045`, `20250512_103045` (Samsung),
     *   `Screenshot_20250512-103045`, `Screenshot_2025-05-12-10-30-45` (local time);
     * - a date only: `IMG-20250512-WA0003` (WhatsApp). Then the modification time, if it's on that day, else noon.
     *
     * A date after the file was last changed can't be its capture date, so it isn't used.
     */
    fun fromName(fileName: String, modifiedAtMillis: Long?, zone: ZoneId): Long? {
        val name = fileName.substringBeforeLast('.')
        val found = dateTime(name, zone) ?: dateOnly(name, modifiedAtMillis, zone) ?: return null
        return found.takeUnless { modifiedAtMillis != null && it > modifiedAtMillis + DAY_MILLIS }
    }

    private fun dateTime(name: String, zone: ZoneId): Long? {
        val match = DATE_TIME.find(name) ?: DASHED_DATE_TIME.find(name) ?: return null
        val local = localDateTime(match.groupValues.subList(1, MILLIS_GROUP).map(String::toInt)) ?: return null
        val millis = match.groups[MILLIS_GROUP]?.value?.padEnd(MILLIS_DIGITS, '0')?.toLong() ?: 0
        // Pixel phones name photos by the UTC time they were taken.
        val at = if (name.startsWith(PIXEL_PREFIX, ignoreCase = true)) ZoneOffset.UTC else zone
        return local.atZone(at).toInstant().toEpochMilli() + millis
    }

    private fun dateOnly(name: String, modifiedAtMillis: Long?, zone: ZoneId): Long? {
        val (year, month, day) = DATE.find(name)?.destructured ?: return null
        val date = localDate(year.toInt(), month.toInt(), day.toInt()) ?: return null
        val modified = modifiedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
        if (modified?.toLocalDate() == date) return modifiedAtMillis
        return date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    }

    private fun localDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day).takeIf { it.year in YEARS }
    } catch (_: DateTimeException) {
        null
    }

    /** [parts]: the year, month, day, hour, minute and second, in that order. */
    private fun localDateTime(parts: List<Int>): LocalDateTime? {
        val (year, month, day) = parts
        val date = localDate(year, month, day) ?: return null
        return try {
            date.atTime(parts[HOUR], parts[MINUTE], parts[SECOND])
        } catch (_: DateTimeException) {
            null
        }
    }

    private const val PIXEL_PREFIX = "PXL_"
    private const val HOUR = 3
    private const val MINUTE = 4
    private const val SECOND = 5
    private const val MILLIS_GROUP = 7
    private const val MILLIS_DIGITS = 3
    private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
    private val YEARS = 1990..2099

    /** `20250512_103045`, `20250512-103045`, with up to three digits of milliseconds after it. */
    private val DATE_TIME = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(\d{1,3})?(?!\d)""")

    /** `2025-05-12-10-30-45` or `2025-05-12_10.30.45`. */
    private val DASHED_DATE_TIME =
        Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})[-_ ](\d{2})[-.](\d{2})[-.](\d{2})(?:-(\d{1,3}))?(?!\d)""")

    /** `20250512` or `2025-05-12`, on its own. */
    private val DATE = Regex("""(?<!\d)(\d{4})-?(\d{2})-?(\d{2})(?!\d)""")
}
