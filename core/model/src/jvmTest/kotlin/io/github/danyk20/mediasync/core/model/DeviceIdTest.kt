/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class DeviceIdTest {

    private val bytes = ByteArray(32) { it.toByte() }

    @Test
    fun `is the lowercase hex of the certificate hash`() {
        DeviceId.of(bytes).hex shouldBe "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    }

    @Test
    fun `rejects anything that isn't a SHA-256`() {
        shouldThrow<IllegalArgumentException> { DeviceId.of(ByteArray(31)) }
        shouldThrow<IllegalArgumentException> { DeviceId("abc") }
        shouldThrow<IllegalArgumentException> { DeviceId("G".repeat(64)) }
        shouldThrow<IllegalArgumentException> { DeviceId("A".repeat(64)) }
    }

    @Test
    fun `toString shows only a short prefix`() {
        val id = DeviceId.of(bytes)

        id.short shouldBe "00010203"
        id.toString() shouldNotContain id.hex
    }
}
