/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestFileSizeTest {

    @Test
    fun aTestFileIs25MegabytesUnlessTheUserChooses() {
        TestFileSize.DEFAULT_BYTES shouldBe 26_214_400
        TestFileSize.megabytes(TestFileSize.DEFAULT_BYTES) shouldBe "25"
    }

    @Test
    fun sizesFromATenthOfAMegabyteToTenGigabytesAreAllowed() {
        TestFileSize.parseMegabytes("0.1") shouldBe TestFileSize.MIN_BYTES
        TestFileSize.parseMegabytes("25") shouldBe TestFileSize.DEFAULT_BYTES
        TestFileSize.parseMegabytes("2,5") shouldBe 2_621_440
        TestFileSize.parseMegabytes("10240") shouldBe TestFileSize.MAX_BYTES
        TestFileSize.MAX_BYTES shouldBe 10L * 1024 * 1024 * 1024
    }

    @Test
    fun sizesOutsideTheRangeOrNotNumbersAreRefused() {
        listOf("", "abc", "0", "0.09", "-5", "10240.5", "20000", "1e3").forEach {
            TestFileSize.parseMegabytes(it).shouldBeNull()
        }
        TestFileSize.clamp(Long.MAX_VALUE) shouldBe TestFileSize.MAX_BYTES
    }
}
