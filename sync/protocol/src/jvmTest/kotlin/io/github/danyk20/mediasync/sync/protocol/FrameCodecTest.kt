/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FrameCodecTest {

    @Test
    fun `encodes type, LEB128 length and payload`() {
        val buffer = Buffer()

        FrameCodec.write(Frame(FrameType.HELLO, "hi".encodeUtf8()), buffer)

        buffer.readByteString() shouldBe "01026869".decodeHex()
    }

    @Test
    fun `encodes a 300 byte length in two bytes`() {
        val buffer = Buffer()

        FrameCodec.writeHeader(FrameHeader(FrameType.DATA, 300), buffer)

        buffer.readByteString() shouldBe "07ac02".decodeHex()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 127, 128, 16_383, 16_384, 2_097_151, FrameCodec.MAX_PAYLOAD_SIZE])
    fun `round-trips payload lengths at every varint boundary`(length: Int) {
        val frame = Frame(FrameType.DATA, ByteArray(length) { it.toByte() }.toByteString())

        val decoded = roundTrip(frame)

        decoded shouldBe frame
    }

    @Test
    fun `round-trips any frame type and payload`() = runTest {
        checkAll(Arb.int(0..FrameType.MAX_CODE), Arb.byteArray(Arb.int(0..4_096), Arb.byte())) { type, payload ->
            val frame = Frame(FrameType(type), payload.toByteString())

            roundTrip(frame) shouldBe frame
        }
    }

    @Test
    fun `reads consecutive frames in order, then reports a clean end`() = runTest {
        val frameArb = Arb.byteArray(Arb.int(0..512), Arb.byte())
        checkAll(Arb.list(frameArb, 0..20)) { payloads ->
            val frames = payloads.mapIndexed { i, p -> Frame(FrameType(i % 256), p.toByteString()) }
            val buffer = Buffer()
            frames.forEach { FrameCodec.write(it, buffer) }

            val decoded = generateSequence { FrameCodec.read(buffer) }.toList()

            decoded shouldBe frames
        }
    }

    @Test
    fun `returns null for an empty stream`() {
        FrameCodec.read(Buffer()).shouldBeNull()
    }

    @Test
    fun `rejects a stream that ends inside the header`() {
        val buffer = Buffer().write("0780".decodeHex())

        val error = shouldThrow<ProtocolException> { FrameCodec.read(buffer) }

        error.message shouldContain "header"
    }

    @Test
    fun `rejects a stream that ends inside the payload`() {
        val buffer = Buffer().write("0105aabb".decodeHex())

        val error = shouldThrow<ProtocolException> { FrameCodec.read(buffer) }

        error.message shouldContain "payload"
    }

    @Test
    fun `rejects a declared length above the limit before allocating it`() {
        // 0x80 0x80 0x81 0x01 = 2_113_536 bytes, just over MAX_PAYLOAD_SIZE.
        val buffer = Buffer().write("0780808101".decodeHex())

        val error = shouldThrow<ProtocolException> { FrameCodec.read(buffer) }

        error.message shouldContain "exceeds"
    }

    @Test
    fun `rejects a length encoded in more than four bytes`() {
        val buffer = Buffer().write("078080808001".decodeHex())

        shouldThrow<ProtocolException> { FrameCodec.read(buffer) }
    }

    @Test
    fun `refuses to write a payload above the limit`() {
        val oversized = Frame(FrameType.DATA, ByteString.of(*ByteArray(FrameCodec.MAX_PAYLOAD_SIZE + 1)))

        shouldThrow<IllegalArgumentException> { FrameCodec.write(oversized, Buffer()) }
    }

    @Test
    fun `random bytes never fail with anything but a protocol error`() = runTest {
        checkAll(Arb.byteArray(Arb.int(0..256), Arb.byte())) { bytes ->
            val buffer = Buffer().write(bytes)
            try {
                generateSequence { FrameCodec.read(buffer) }.count()
            } catch (expected: ProtocolException) {
                // Malformed input is reported as a ProtocolException; anything else fails the test.
            }
        }
    }

    @Test
    fun `frame types outside a byte are rejected`() {
        shouldThrow<IllegalArgumentException> { FrameType(256) }
        shouldThrow<IllegalArgumentException> { FrameType(-1) }
    }

    private fun roundTrip(frame: Frame): Frame? {
        val buffer = Buffer()
        FrameCodec.write(frame, buffer)
        return FrameCodec.read(buffer).also { buffer.exhausted() shouldBe true }
    }
}
