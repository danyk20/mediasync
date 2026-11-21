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
        LogSize.parseMegabytes(".5") shouldBe 524_288
        LogSize.parseMegabytes("0,5") shouldBe 524_288
    }

    @Test
    fun sizesOutsideTheRangeOrNotNumbersAreRefused() {
        listOf(
            "", "abc", "0", "0.09", "-1", "1024.5", "5000", "1e3", "1.2.3", "0.0999", "1025", "1234567890", "1.", " ",
        ).forEach {
            LogSize.parseMegabytes(it).shouldBeNull()
        }
    }

    @Test
    fun roundingIsHalfUpToHundredths() {
        LogSize.megabytes(131_072) shouldBe "0.13"
        LogSize.megabytes(393_216) shouldBe "0.38"
        LogSize.megabytes(5_243) shouldBe "0.01"
        LogSize.megabytes(5_242) shouldBe "0"
        LogSize.megabytes(0) shouldBe "0"
        LogSize.megabytes(1_048_577) shouldBe "1"
        LogSize.megabytes(1_572_864) shouldBe "1.5"
        LogSize.megabytes(10 * LogSize.MEGABYTE) shouldBe "10"
        LogSize.megabytes(1_024 * LogSize.MEGABYTE) shouldBe "1024"
        LogSize.megabytes(Long.MAX_VALUE).isNotEmpty() shouldBe true
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
