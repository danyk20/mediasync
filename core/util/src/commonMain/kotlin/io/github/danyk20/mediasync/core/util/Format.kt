/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import kotlin.math.floor

private const val TENTHS = 10
private const val HUNDREDTHS = 100
private const val HUNDREDTHS_DIGITS = 2
private const val HALF = 0.5

/**
 * [value] with one decimal, rounded half up, as "%.1f" formats it. For log text only: the results can differ from the
 * JVM's formatter when a value sits within one floating-point step of a tie.
 */
fun oneDecimal(value: Double): String {
    if (!value.isFinite() || value < 0) return value.toString()
    val tenths = floor(value * TENTHS + HALF).toLong()
    return "${tenths / TENTHS}.${tenths % TENTHS}"
}

/**
 * [value] with two decimals, rounded half up, as "%.2f" formats it (`1.05`). For log text only: the results can differ
 * from the JVM's formatter when a value sits within one floating-point step of a tie.
 */
fun twoDecimals(value: Double): String {
    if (!value.isFinite() || value < 0) return value.toString()
    val hundredths = floor(value * HUNDREDTHS + HALF).toLong()
    return "${hundredths / HUNDREDTHS}.${(hundredths % HUNDREDTHS).toString().padStart(HUNDREDTHS_DIGITS, '0')}"
}
