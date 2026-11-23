/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import kotlin.math.absoluteValue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/** [instant] in [zone] as "2026-10-14 09:30:00.250 +0200": date, time to the millisecond, offset without a colon. */
internal fun formatTimestamp(instant: Instant, zone: TimeZone): String {
    val time = instant.toLocalDateTime(zone)
    val offset = zone.offsetAt(instant).totalSeconds
    val sign = if (offset < 0) '-' else '+'
    val hours = offset.absoluteValue / SECONDS_PER_HOUR
    val minutes = offset.absoluteValue % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val date = "${time.year.pad(YEAR_WIDTH)}-${(time.month.ordinal + 1).pad()}-${time.day.pad()}"
    val clock = "${time.hour.pad()}:${time.minute.pad()}:${time.second.pad()}"
    val millis = (time.nanosecond / NANOS_PER_MILLI).pad(MILLIS_WIDTH)
    return "$date $clock.$millis $sign${hours.pad()}${minutes.pad()}"
}

private fun Int.pad(width: Int = 2) = toString().padStart(width, '0')

private const val YEAR_WIDTH = 4
private const val MILLIS_WIDTH = 3
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val NANOS_PER_MILLI = 1_000_000
