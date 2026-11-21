/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

private const val KB = 1000.0
private const val UNITS = "kMGT"

/** File sizes the way people read them: "830 MB", "1.2 GB" (decimal units, like phone storage settings). */
fun formatBytes(bytes: Long): String = formatBytesWith(bytes, ::formatFixed)

internal fun formatBytesWith(bytes: Long, fixed: (Double, Int) -> String): String {
    if (bytes < KB) return "$bytes B"
    var value = bytes / KB
    var unit = 0
    while (value >= KB && unit < UNITS.lastIndex) {
        value /= KB
        unit++
    }
    return "${fixed(value, if (value < 10) 1 else 0)} ${UNITS[unit]}B"
}

/**
 * A size the user typed in megabytes (1024 × 1024 bytes, as the diagnostic log's or a test file's), shown the same
 * way: "25 MB", "0.5 MB", with up to two decimals.
 */
fun formatMegabytes(bytes: Long): String = formatMegabytesWith(bytes, ::formatUpToTwoDecimals)

internal fun formatMegabytesWith(bytes: Long, upToTwoDecimals: (Double) -> String): String =
    upToTwoDecimals(bytes / MEBIBYTE) + " MB"

private const val MEBIBYTE = 1024.0 * 1024.0
