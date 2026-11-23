/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * EXIF's `yyyy:MM:dd HH:mm:ss`, read the way `DateTimeFormatter.ofPattern` (SMART) read it: a day past the month's end
 * moves to its last day, `24:00:00` is midnight at the day's end, year 0 and anything else out of range is no date.
 * `null` for text of another shape (cameras without a clock write zeros).
 */
internal fun parseExifDate(text: String): LocalDateTime? {
    val match = EXIF_DATE.matchEntire(text) ?: return null
    val parts = match.groupValues.drop(1).map(String::toInt)
    val (year, month, day) = parts.take(DATE_PARTS)
    val (hour, minute, second) = parts.drop(DATE_PARTS)
    if (!validDate(year, month, day) || minute > MAX_MINUTE || second > MAX_MINUTE) return null
    val date = LocalDate(year, month, minOf(day, daysIn(year, month)))
    return when {
        hour < HOURS -> LocalDateTime(date, LocalTime(hour, minute, second))
        hour == HOURS && minute == 0 && second == 0 -> LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        else -> null
    }
}

/** Years from 1 (year 0 is no date in the EXIF reader this replaces), months 1 to 12, days 1 to 31. */
private fun validDate(year: Int, month: Int, day: Int): Boolean = year >= 1 && month in 1..MONTHS && day in 1..MAX_DAY

private fun daysIn(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private val EXIF_DATE = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""")
private const val DATE_PARTS = 3
private const val MONTHS = 12
private const val MAX_DAY = 31
private const val MAX_MINUTE = 59
private const val HOURS = 24
