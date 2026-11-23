/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlin.math.floor

private const val TENTHS = 10
private const val HALF = 0.5

/**
 * [value] with one decimal, rounded half up, as "%.1f" formats it. For log text only: the results can differ from the
 * JVM's formatter when a value sits within one floating-point step of a tie.
 */
internal fun oneDecimal(value: Double): String {
    if (!value.isFinite() || value < 0) return value.toString()
    val tenths = floor(value * TENTHS + HALF).toLong()
    return "${tenths / TENTHS}.${tenths % TENTHS}"
}
