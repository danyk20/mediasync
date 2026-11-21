/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaDayOfWeek
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toJavaZoneId

internal actual fun formatFixed(value: Double, fractionDigits: Int): String =
    String.format(Locale.getDefault(), "%.${fractionDigits}f", value)

internal actual fun formatUpToTwoDecimals(value: Double): String =
    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(value)

@Composable
internal actual fun formatTimeOfDay(time: LocalTime): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time.toJavaLocalTime())

@Composable
internal actual fun weekdayName(day: DayOfWeek): String =
    day.toJavaDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault())

@Composable
internal actual fun formatMediumDate(moment: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    .withZone(java.time.ZoneId.systemDefault())
    .format(java.time.Instant.ofEpochMilli(moment.toEpochMilliseconds()))

internal actual fun isAfterDaysBefore(moment: Instant, now: Instant, zone: TimeZone, days: Int): Boolean {
    val nowZoned = ZonedDateTime.ofInstant(now.toJavaInstant(), zone.toJavaZoneId())
    return moment.toJavaInstant().isAfter(nowZoned.minusDays(days.toLong()).toInstant())
}
