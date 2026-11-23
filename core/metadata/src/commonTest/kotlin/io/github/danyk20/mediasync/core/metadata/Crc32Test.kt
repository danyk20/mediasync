/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Crc32Test {
    @Test
    fun theCheckValueOfTheStandardIsRight() {
        crc32("123456789".encodeToByteArray()) shouldBe 0xCBF43926L
    }

    @Test
    fun anEmptyInputIsZero() {
        crc32(ByteArray(0)) shouldBe 0L
    }

    @Test
    fun aPngEndChunkTypeHasItsKnownChecksum() {
        crc32("IEND".latin1()) shouldBe 0xAE426082L
    }
}
