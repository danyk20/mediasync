/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.danyk20.mediasync.sync.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

/** Maps [ControlMessage]s to and from [Frame]s. */
object MessageCodec {

    private val protoBuf = ProtoBuf { encodeDefaults = false }

    private val decoders: Map<FrameType, DeserializationStrategy<ControlMessage>> = mapOf(
        FrameType.HELLO to Hello.serializer(),
        FrameType.PAIR_REQUEST to PairRequest.serializer(),
        FrameType.PAIR_RESULT to PairResult.serializer(),
        FrameType.OFFER to Offer.serializer(),
        FrameType.OFFER_RESPONSE to OfferResponse.serializer(),
        FrameType.FILE_BEGIN to FileBegin.serializer(),
        FrameType.FILE_END to FileEnd.serializer(),
        FrameType.FILE_ACK to FileAck.serializer(),
        FrameType.BYE to Bye.serializer(),
        FrameType.LINK_TEST_BEGIN to LinkTestBegin.serializer(),
        FrameType.LINK_TEST_END to LinkTestEnd.serializer(),
        FrameType.LINK_TEST_RESULT to LinkTestResult.serializer(),
        FrameType.TURBO_REQUEST to TurboRequest.serializer(),
        FrameType.TURBO_READY to TurboReady.serializer(),
    )

    @Suppress("CyclomaticComplexMethod") // One branch per message type, checked to be exhaustive by the compiler.
    fun encode(message: ControlMessage): Frame = when (message) {
        is Hello -> frame(FrameType.HELLO, Hello.serializer(), message)
        is PairRequest -> frame(FrameType.PAIR_REQUEST, PairRequest.serializer(), message)
        is PairResult -> frame(FrameType.PAIR_RESULT, PairResult.serializer(), message)
        is Offer -> frame(FrameType.OFFER, Offer.serializer(), message)
        is OfferResponse -> frame(FrameType.OFFER_RESPONSE, OfferResponse.serializer(), message)
        is FileBegin -> frame(FrameType.FILE_BEGIN, FileBegin.serializer(), message)
        is FileEnd -> frame(FrameType.FILE_END, FileEnd.serializer(), message)
        is FileAck -> frame(FrameType.FILE_ACK, FileAck.serializer(), message)
        is Bye -> frame(FrameType.BYE, Bye.serializer(), message)
        is LinkTestBegin -> frame(FrameType.LINK_TEST_BEGIN, LinkTestBegin.serializer(), message)
        is LinkTestEnd -> frame(FrameType.LINK_TEST_END, LinkTestEnd.serializer(), message)
        is LinkTestResult -> frame(FrameType.LINK_TEST_RESULT, LinkTestResult.serializer(), message)
        is TurboRequest -> frame(FrameType.TURBO_REQUEST, TurboRequest.serializer(), message)
        is TurboReady -> frame(FrameType.TURBO_READY, TurboReady.serializer(), message)
    }

    /**
     * Decodes a control frame.
     *
     * @return the message, or `null` for frame types this version doesn't know (they are ignored).
     * @throws ProtocolException if a known frame type carries a malformed payload.
     */
    fun decode(frame: Frame): ControlMessage? {
        require(frame.type != FrameType.DATA) { "DATA frames carry raw bytes, not control messages" }
        val decoder = decoders[frame.type] ?: return null
        return runCatching { protoBuf.decodeFromByteArray(decoder, frame.payload.toByteArray()) }
            .getOrElse { error ->
                if (!error.isMalformedInput()) throw error
                throw ProtocolException("Malformed ${decoder.descriptor.serialName} message", error)
            }
    }

    /** kotlinx-protobuf reports malformed input with several exception types; all of them mean "bad peer". */
    private fun Throwable.isMalformedInput() = this is SerializationException ||
        this is IllegalArgumentException ||
        this is IllegalStateException ||
        this is IndexOutOfBoundsException

    private fun <T : ControlMessage> frame(type: FrameType, serializer: SerializationStrategy<T>, message: T) =
        Frame(type, protoBuf.encodeToByteArray(serializer, message).toByteString())
}
