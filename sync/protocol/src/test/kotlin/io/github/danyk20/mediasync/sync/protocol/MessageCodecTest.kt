/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

class MessageCodecTest {

    private val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".decodeHex()

    @Test
    fun `every message type round-trips through a frame`() {
        val messages = listOf(
            Hello(PROTOCOL_VERSION, "Anna's Pixel", WireRole.PRODUCER, "0.2.0"),
            Hello(PROTOCOL_VERSION, "Pixel 2 XL", WireRole.CONSUMER, freeSpaceBytes = 12_345_678_901),
            Hello(PROTOCOL_VERSION, "Pixel 2 XL", WireRole.CONSUMER, schedule = "hours:6@03:00"),
            Hello(
                PROTOCOL_VERSION,
                "Pixel 2 XL",
                WireRole.CONSUMER,
                turboKey = ByteArray(32) {
                    it.toByte()
                }.toByteString(),
            ),
            PairRequest(token = "0123456789abcdef".encodeUtf8()),
            PairResult(accepted = false, reason = PairResult.Reason.DECLINED),
            Offer(listOf(offerItem(1), offerItem(2).copy(capturedAtMillis = null, album = null))),
            OfferResponse(want = listOf(Want(1, resumeOffset = 4096)), have = listOf(Have(2, sha))),
            FileBegin(offerId = 7, offset = 1_048_576),
            FileEnd(offerId = 7, sha256 = sha),
            FileAck(offerId = 7, status = FileAck.Status.OK, contentId = sha),
            Bye(Bye.Reason.CANCELLED),
            LinkTestBegin(),
            LinkTestEnd(bytes = 1_234_567_890),
            LinkTestResult(bytes = 1_234_567_890, micros = 5_000_123),
            TurboRequest(),
            TurboReady(TurboReady.Status.OK, "DIRECT-ab-MediaSync", "passphrase-1234", "192.168.49.1", 47_420),
            TurboReady(TurboReady.Status.NOT_ALLOWED),
        )

        messages.forEach { message ->
            MessageCodec.decode(MessageCodec.encode(message)) shouldBe message
        }
    }

    @Test
    fun `messages map to their frame types`() {
        MessageCodec.encode(Hello(1, "x", WireRole.CONSUMER)).type shouldBe FrameType.HELLO
        MessageCodec.encode(OfferResponse()).type shouldBe FrameType.OFFER_RESPONSE
        MessageCodec.encode(Bye()).type shouldBe FrameType.BYE
    }

    @Test
    fun `offer items with any metadata round-trip`() = runTest {
        checkAll(
            Arb.int(0..Int.MAX_VALUE),
            Arb.long(0L..Long.MAX_VALUE),
            Arb.string(0..60),
            Arb.long().orNull(),
            Arb.int(-720..840).orNull(),
            Arb.boolean(),
            Arb.byteArray(Arb.int(32..32), Arb.byte()),
        ) { offerId, size, name, capturedAt, offset, favorite, fingerprint ->
            val item = OfferItem(
                offerId = offerId,
                size = size,
                fingerprint = fingerprint.toByteString(),
                mimeType = "image/jpeg",
                displayName = name,
                capturedAtMillis = capturedAt,
                utcOffsetMinutes = offset,
                favorite = favorite,
            )

            MessageCodec.decode(MessageCodec.encode(Offer(listOf(item)))) shouldBe Offer(listOf(item))
        }
    }

    @Test
    fun `fields added by a newer version are ignored`() {
        // A Hello with an extra field 15 (varint 1) appended, as a future version might send.
        val current = MessageCodec.encode(Hello(1, "x", WireRole.CONSUMER))
        val extended = Frame(FrameType.HELLO, (current.payload.hex() + "7801").decodeHex())

        MessageCodec.decode(extended) shouldBe Hello(1, "x", WireRole.CONSUMER)
    }

    @Test
    fun `unknown frame types are skipped, not rejected`() {
        MessageCodec.decode(Frame(FrameType(0x7F), "anything".encodeUtf8())).shouldBeNull()
    }

    @Test
    fun `a malformed payload is a protocol error`() {
        // Field 1 declared as a 10-byte string but only 2 bytes follow.
        val broken = Frame(FrameType.PAIR_REQUEST, "0a0aabcd".decodeHex())

        shouldThrow<ProtocolException> { MessageCodec.decode(broken) }
    }

    @Test
    fun `random payloads never fail with anything but a protocol error`() = runTest {
        checkAll(Arb.int(1..0x0A), Arb.byteArray(Arb.int(0..64), Arb.byte())) { type, bytes ->
            val frame = Frame(FrameType(type), bytes.toByteString())
            if (frame.type == FrameType.DATA) return@checkAll
            try {
                MessageCodec.decode(frame)
            } catch (expected: ProtocolException) {
                // Malformed input must surface as ProtocolException; anything else fails the test.
            }
        }
    }

    @Test
    fun `DATA frames are not control messages`() {
        shouldThrow<IllegalArgumentException> { MessageCodec.decode(Frame(FrameType.DATA, sha)) }
    }

    @Test
    fun `lists of offer items keep their order`() = runTest {
        checkAll(Arb.list(Arb.int(0..1_000), 0..50)) { ids ->
            val offer = Offer(ids.map(::offerItem))

            (MessageCodec.decode(MessageCodec.encode(offer)) as Offer).items.map { it.offerId } shouldBe ids
        }
    }

    private fun offerItem(id: Int) = OfferItem(
        offerId = id,
        size = 3_500_000L + id,
        fingerprint = sha,
        mimeType = "image/jpeg",
        displayName = "PXL_20260512_134501$id.jpg",
        capturedAtMillis = 1_778_593_501_000L,
        utcOffsetMinutes = 120,
        album = "Camera",
        favorite = id % 2 == 0,
        modifiedAtMillis = 1_778_593_502_000L,
    )
}
