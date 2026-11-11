/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How much of the diagnostic log is kept (docs/PLAN.md §11): [DEFAULT_BYTES] until the user chooses, from
 * [MIN_BYTES] to [MAX_BYTES]. The user types it in megabytes (MB, 1024 × 1024 bytes).
 */
object LogSize {
    const val MEGABYTE = 1024L * 1024

    /** 1 MB. */
    const val DEFAULT_BYTES = MEGABYTE

    /** 0.1 MB: enough for a few days of entries. */
    const val MIN_BYTES = MEGABYTE / 10

    /** 1024 MB. */
    const val MAX_BYTES = 1024 * MEGABYTE

    /** [bytes] within what's allowed. */
    fun clamp(bytes: Long): Long = bytes.coerceIn(MIN_BYTES, MAX_BYTES)

    /**
     * The size in [text], such as `0.5`, `20` or `1,5`, in megabytes; `null` if it isn't a number or isn't from
     * 0.1 to 1024 MB.
     */
    fun parseMegabytes(text: String): Long? {
        val number = text.trim().replace(',', '.').takeIf { NUMBER.matches(it) } ?: return null
        val megabytes = BigDecimal(number)
        if (megabytes < MIN_MEGABYTES || megabytes > MAX_MEGABYTES) return null
        return megabytes.multiply(BigDecimal.valueOf(MEGABYTE)).setScale(0, RoundingMode.DOWN).toLong()
    }

    /** [bytes] in megabytes, with no more decimals than needed: `1`, `0.5`, `20.25`. */
    fun megabytes(bytes: Long): String = BigDecimal.valueOf(bytes)
        .divide(BigDecimal.valueOf(MEGABYTE), 2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

    private val NUMBER = Regex("""\d{1,9}(\.\d{1,9})?|\.\d{1,9}""")
    private val MIN_MEGABYTES = BigDecimal("0.1")
    private val MAX_MEGABYTES = BigDecimal(MAX_BYTES / MEGABYTE)
}
