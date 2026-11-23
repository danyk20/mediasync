/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ByteBufferTest {
    @Test
    fun bigEndianIsTheDefault() {
        ByteBuffer.allocate(8).putInt(0x01020304).putShort(5).array().toList() shouldBe
            listOf<Byte>(1, 2, 3, 4, 0, 5, 0, 0)
    }

    @Test
    fun littleEndianReadsAndWritesTheSameBytesReversed() {
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x01020304).array().toList() shouldBe
            listOf<Byte>(4, 3, 2, 1)
        ByteBuffer.wrap(byteArrayOf(4, 3, 2, 1)).order(ByteOrder.LITTLE_ENDIAN).getInt(0) shouldBe 0x01020304
        val negative = ByteBuffer.allocate(2).putShort(-2)
        negative.getShort(0) shouldBe (-2).toShort()
    }

    @Test
    fun relativeReadsMovePositionAndAbsoluteOnesDoNot() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0, 1, 0, 2, 0, 3))
        buffer.getShort().toInt() shouldBe 1
        buffer.position() shouldBe 2
        buffer.getShort(4).toInt() shouldBe 3
        buffer.position() shouldBe 2
    }

    @Test
    fun readingPastTheEndFailsAsIndexOutOfBounds() {
        shouldThrow<IndexOutOfBoundsException> { ByteBuffer.wrap(ByteArray(7)).getLong(0) }
    }

    @Test
    fun writingPastTheEndIsAnIllegalState() {
        shouldThrow<IllegalStateException> { ByteBuffer.allocate(2).putInt(1) }
    }
}
