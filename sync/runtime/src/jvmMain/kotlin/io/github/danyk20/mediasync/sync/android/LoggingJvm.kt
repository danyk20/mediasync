/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import java.time.Duration

/** A span of time for the log, in whole units: "6 h 12 min", "42 min" or "42 s". */
fun duration(duration: Duration): String {
    val minutes = duration.toMinutes()
    return when {
        minutes >= MINUTES_PER_HOUR -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        minutes > 0 -> "$minutes min"
        else -> "${duration.seconds} s"
    }
}

private const val MINUTES_PER_HOUR = 60L
