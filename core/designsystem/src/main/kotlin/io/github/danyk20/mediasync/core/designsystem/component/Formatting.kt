/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import java.util.Locale

private const val KB = 1000.0
private const val UNITS = "kMGT"

/** File sizes the way people read them: "830 MB", "1.2 GB" (decimal units, like phone storage settings). */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < KB) return "$bytes B"
    var value = bytes / KB
    var unit = 0
    while (value >= KB && unit < UNITS.lastIndex) {
        value /= KB
        unit++
    }
    val pattern = if (value < 10) "%.1f %sB" else "%.0f %sB"
    return String.format(locale, pattern, value, UNITS[unit])
}
