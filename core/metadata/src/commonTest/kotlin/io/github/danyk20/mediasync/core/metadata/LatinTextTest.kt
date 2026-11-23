/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LatinTextTest {
    @Test
    fun latin1RoundTripsEveryByte() {
        val all = ByteArray(256) { it.toByte() }
        all.latin1String().latin1().toList() shouldBe all.toList()
    }

    @Test
    fun charactersBeyondLatin1BecomeQuestionMarks() {
        "a€".latin1().toList() shouldBe listOf<Byte>(97, 63)
    }

    @Test
    fun latin1StringChecksItsRange() {
        shouldThrow<IndexOutOfBoundsException> { byteArrayOf(1, 2).latin1String(1, 5) }
    }
}
