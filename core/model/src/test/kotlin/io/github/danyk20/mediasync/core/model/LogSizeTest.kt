/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LogSizeTest {

    @Test
    fun theLogKeepsOneMegabyteUntilTheUserChooses() {
        LogSize.DEFAULT_BYTES shouldBe 1_048_576
        LogSize.megabytes(LogSize.DEFAULT_BYTES) shouldBe "1"
    }

    @Test
    fun sizesAreTypedInMegabytesWithAPointOrAComma() {
        LogSize.parseMegabytes("1") shouldBe 1_048_576
        LogSize.parseMegabytes(" 0.5 ") shouldBe 524_288
        LogSize.parseMegabytes("1,5") shouldBe 1_572_864
        LogSize.parseMegabytes("20") shouldBe 20 * LogSize.MEGABYTE
        LogSize.parseMegabytes("0.1") shouldBe LogSize.MIN_BYTES
        LogSize.parseMegabytes("1024") shouldBe LogSize.MAX_BYTES
    }

    @Test
    fun sizesOutsideTheRangeOrNotNumbersAreRefused() {
        listOf("", "abc", "0", "0.09", "-1", "1024.5", "5000", "1e3", "1.2.3").forEach {
            LogSize.parseMegabytes(it).shouldBeNull()
        }
    }

    @Test
    fun storedSizesStayInTheRangeAndShowWithFewDecimals() {
        LogSize.clamp(1) shouldBe LogSize.MIN_BYTES
        LogSize.clamp(Long.MAX_VALUE) shouldBe LogSize.MAX_BYTES
        LogSize.clamp(5 * LogSize.MEGABYTE) shouldBe 5 * LogSize.MEGABYTE
        LogSize.megabytes(524_288) shouldBe "0.5"
        LogSize.megabytes(LogSize.MIN_BYTES) shouldBe "0.1"
        LogSize.megabytes(20 * LogSize.MEGABYTE + LogSize.MEGABYTE / 4) shouldBe "20.25"
    }
}
