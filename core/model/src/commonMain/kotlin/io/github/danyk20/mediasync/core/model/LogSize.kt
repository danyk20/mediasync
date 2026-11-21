/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/**
 * A size the user types in megabytes (MB, 1024 × 1024 bytes), from [minBytes] to [maxBytes]: such as `0.5`, `20` or
 * `1,5`.
 */
abstract class MegabyteSize(val minBytes: Long, val maxBytes: Long) {
    /** [bytes] within what's allowed. */
    fun clamp(bytes: Long): Long = bytes.coerceIn(minBytes, maxBytes)

    /** The size in [text], in megabytes; `null` if it isn't a number or isn't from [minBytes] to [maxBytes]. */
    fun parseMegabytes(text: String): Long? {
        val number = text.trim().replace(',', '.').takeIf { NUMBER.matches(it) } ?: return null
        val whole = number.substringBefore('.').ifEmpty { "0" }.toLong()
        val fraction = number.substringAfter('.', missingDelimiterValue = "")
        // Exact, rounded down: at most 9 digits on each side, so nothing overflows a Long. A tenth of a megabyte is
        // 104857.6 bytes: rounded down, 0.1 MB is still the smallest allowed.
        val fractionBytes = if (fraction.isEmpty()) 0L else fraction.toLong() * MEGABYTE / tenTo(fraction.length)
        val bytes = whole * MEGABYTE + fractionBytes
        return bytes.takeIf { it in minBytes..maxBytes }
    }

    /** [bytes] in megabytes, with no more decimals than needed: `1`, `0.5`, `20.25`. */
    fun megabytes(bytes: Long): String {
        // Hundredths of a megabyte, rounded half up; split so nothing overflows.
        val whole = bytes / MEGABYTE
        val rest = bytes % MEGABYTE
        val hundredths = whole * HUNDRED + (rest * HUNDRED * 2 + MEGABYTE) / (2 * MEGABYTE)
        val units = hundredths / HUNDRED
        val cents = hundredths % HUNDRED
        return when {
            cents == 0L -> "$units"
            cents % TEN == 0L -> "$units.${cents / TEN}"
            else -> "$units.${cents.toString().padStart(2, '0')}"
        }
    }

    companion object {
        const val MEGABYTE = 1024L * 1024
        private val NUMBER = Regex("""\d{1,9}(\.\d{1,9})?|\.\d{1,9}""")
        private const val TEN = 10L
        private const val HUNDRED = 100L

        private fun tenTo(power: Int): Long = (1..power).fold(1L) { result, _ -> result * TEN }
    }
}

/**
 * How much of the diagnostic log is kept (docs/PLAN.md §11): [DEFAULT_BYTES] until the user chooses, from
 * [MIN_BYTES] to [MAX_BYTES].
 */
object LogSize : MegabyteSize(MegabyteSize.MEGABYTE / TENTH, LOG_MAX_MEGABYTES * MegabyteSize.MEGABYTE) {
    const val MEGABYTE = MegabyteSize.MEGABYTE

    /** 1 MB. */
    const val DEFAULT_BYTES = MEGABYTE

    /** 0.1 MB: enough for a few days of entries. */
    const val MIN_BYTES = MEGABYTE / 10

    /** 1024 MB. */
    const val MAX_BYTES = 1024 * MEGABYTE
}

/**
 * The size of a test file (docs/PLAN.md §7, *Test file*): [DEFAULT_BYTES] unless the user chooses, from [MIN_BYTES]
 * to [MAX_BYTES].
 */
object TestFileSize : MegabyteSize(MegabyteSize.MEGABYTE / TENTH, TEST_FILE_MAX_MEGABYTES * MegabyteSize.MEGABYTE) {
    /** 25 MB: a few seconds over Wi-Fi, long enough to measure. */
    const val DEFAULT_BYTES = 25 * MEGABYTE

    /** 0.1 MB. */
    const val MIN_BYTES = MEGABYTE / 10

    /** 10 GB (10240 MB). */
    const val MAX_BYTES = 10_240 * MEGABYTE
}

private const val TENTH = 10
private const val LOG_MAX_MEGABYTES = 1024L
private const val TEST_FILE_MAX_MEGABYTES = 10_240L
