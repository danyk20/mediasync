/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** [value] with exactly [fractionDigits] decimals, in the user's language: "1.2" or "1,2". */
internal expect fun formatFixed(value: Double, fractionDigits: Int): String

/** [value] with up to two decimals and no trailing zeros, in the user's language: "25", "0.5" or "0,5". */
internal expect fun formatUpToTwoDecimals(value: Double): String

/** A time of day the way the device shows times: "3:00 AM" or "03:00", following the 12/24-hour choice. */
@Composable
internal expect fun formatTimeOfDay(time: LocalTime): String

/** The full name of a day of the week, in the user's language. */
@Composable
internal expect fun weekdayName(day: DayOfWeek): String

/** A date the way the device shows dates: "Oct 2, 2026". */
@Composable
internal expect fun formatMediumDate(moment: Instant): String

/**
 * Whether [moment] is after the instant that is [days] calendar days before [now], in [zone]: the same wall-clock time
 * on that day. Compared as instants, so a day on which the clocks change keeps the rule the Android app has always
 * used (java.time's `ZonedDateTime.minusDays`).
 */
internal expect fun isAfterDaysBefore(moment: Instant, now: Instant, zone: TimeZone, days: Int): Boolean
