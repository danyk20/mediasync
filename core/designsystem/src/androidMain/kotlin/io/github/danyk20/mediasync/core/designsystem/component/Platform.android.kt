/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Date
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
internal actual fun formatTimeOfDay(time: LocalTime): String {
    val context = LocalContext.current
    LocalConfiguration.current // Recompose when the locale or the 24-hour setting changes.
    val today = LocalDate.now().atTime(time.toJavaLocalTime()).atZone(ZoneId.systemDefault()).toInstant()
    return DateFormat.getTimeFormat(context).format(Date.from(today))
}

@Composable
internal actual fun weekdayName(day: DayOfWeek): String =
    day.toJavaDayOfWeek().getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])

@Composable
internal actual fun formatMediumDate(moment: Instant): String {
    LocalConfiguration.current // Recompose when the locale changes.
    return DateFormat.getMediumDateFormat(LocalContext.current).format(Date(moment.toEpochMilliseconds()))
}

internal actual fun isAfterDaysBefore(moment: Instant, now: Instant, zone: TimeZone, days: Int): Boolean {
    val nowZoned = ZonedDateTime.ofInstant(now.toJavaInstant(), zone.toJavaZoneId())
    return moment.toJavaInstant().isAfter(nowZoned.minusDays(days.toLong()).toInstant())
}
