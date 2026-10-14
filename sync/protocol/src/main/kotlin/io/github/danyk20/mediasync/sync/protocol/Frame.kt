/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.protocol

import okio.ByteString

/**
 * Message type carried in the first byte of every frame.
 *
 * Any value from 0 to 255 is valid on the wire: peers ignore types they don't know, so newer
 * versions can add messages without breaking older ones (docs/PLAN.md §5.1).
 */
@JvmInline
value class FrameType(val code: Int) {
    init {
        require(code in 0..MAX_CODE) { "Frame type must be in 0..$MAX_CODE, was $code" }
    }

    companion object {
        const val MAX_CODE = 0xFF

        val HELLO = FrameType(0x01)
        val PAIR_REQUEST = FrameType(0x02)
        val PAIR_RESULT = FrameType(0x03)
        val OFFER = FrameType(0x04)
        val OFFER_RESPONSE = FrameType(0x05)
        val FILE_BEGIN = FrameType(0x06)

        /** Raw file bytes; the only frame type whose payload isn't a protobuf message. */
        val DATA = FrameType(0x07)
        val FILE_END = FrameType(0x08)
        val FILE_ACK = FrameType(0x09)
        val BYE = FrameType(0x0A)

        /** Reserved for v1.1: the consumer reports cloud backup state back to producers. */
        val BACKUP_STATUS = FrameType(0x0B)
        val LINK_TEST_BEGIN = FrameType(0x0C)
        val LINK_TEST_END = FrameType(0x0D)
        val LINK_TEST_RESULT = FrameType(0x0E)
        val TURBO_REQUEST = FrameType(0x0F)
        val TURBO_READY = FrameType(0x10)
    }
}

/** The fixed part of a frame, read before its payload so large `DATA` payloads can be streamed. */
data class FrameHeader(val type: FrameType, val payloadLength: Int)

/** One unit on a MediaSync stream: `type (1 byte) | payload length (unsigned LEB128) | payload`. */
data class Frame(val type: FrameType, val payload: ByteString) {
    val header: FrameHeader get() = FrameHeader(type, payload.size)
}
