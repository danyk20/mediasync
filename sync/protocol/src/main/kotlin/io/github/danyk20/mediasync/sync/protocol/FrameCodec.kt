/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.protocol

import java.io.IOException
import okio.BufferedSink
import okio.BufferedSource
import okio.EOFException

/** The peer sent bytes that are not a valid MediaSync frame. The session must be closed. */
open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads and writes [Frame]s.
 *
 * Every length is validated before anything is allocated, so a malicious or corrupt peer can't make
 * the receiver allocate more than [MAX_PAYLOAD_SIZE] bytes per frame.
 */
object FrameCodec {
    /** 1 MiB data chunks plus headroom for control messages. */
    const val MAX_PAYLOAD_SIZE: Int = 2 * 1024 * 1024

    private const val VARINT_CONTINUATION = 0x80
    private const val VARINT_VALUE_MASK = 0x7F
    private const val VARINT_BITS_PER_BYTE = 7

    /** [MAX_PAYLOAD_SIZE] needs 22 bits, which LEB128 encodes in at most 4 bytes. */
    private const val MAX_VARINT_BYTES = 4

    fun write(frame: Frame, sink: BufferedSink) {
        writeHeader(frame.header, sink)
        sink.write(frame.payload)
    }

    fun writeHeader(header: FrameHeader, sink: BufferedSink) {
        require(header.payloadLength in 0..MAX_PAYLOAD_SIZE) {
            "Payload of ${header.payloadLength} bytes exceeds the $MAX_PAYLOAD_SIZE byte limit"
        }
        sink.writeByte(header.type.code)
        var remaining = header.payloadLength
        while (remaining >= VARINT_CONTINUATION) {
            sink.writeByte((remaining and VARINT_VALUE_MASK) or VARINT_CONTINUATION)
            remaining = remaining ushr VARINT_BITS_PER_BYTE
        }
        sink.writeByte(remaining)
    }

    /**
     * Reads the next whole frame.
     *
     * @return the frame, or `null` if the stream ended cleanly on a frame boundary.
     * @throws ProtocolException if the bytes are malformed or the stream ends inside a frame.
     */
    fun read(source: BufferedSource): Frame? {
        val header = readHeader(source) ?: return null
        val payload = try {
            source.readByteString(header.payloadLength.toLong())
        } catch (e: EOFException) {
            throw ProtocolException("Stream ended inside a ${header.payloadLength} byte payload", e)
        }
        return Frame(header.type, payload)
    }

    /**
     * Reads only the next frame's header; the caller then consumes exactly
     * [FrameHeader.payloadLength] payload bytes from [source].
     *
     * @return the header, or `null` if the stream ended cleanly on a frame boundary.
     */
    fun readHeader(source: BufferedSource): FrameHeader? {
        if (source.exhausted()) return null
        val type = FrameType(source.readByte().toInt() and FrameType.MAX_CODE)
        val length = try {
            readPayloadLength(source)
        } catch (e: EOFException) {
            throw ProtocolException("Stream ended inside a frame header", e)
        }
        return FrameHeader(type, length)
    }

    private fun readPayloadLength(source: BufferedSource): Int {
        var value = 0
        var shift = 0
        repeat(MAX_VARINT_BYTES) {
            val byte = source.readByte().toInt() and FrameType.MAX_CODE
            value = value or ((byte and VARINT_VALUE_MASK) shl shift)
            if (byte and VARINT_CONTINUATION == 0) {
                if (value > MAX_PAYLOAD_SIZE) {
                    throw ProtocolException("Payload length $value exceeds the $MAX_PAYLOAD_SIZE byte limit")
                }
                return value
            }
            shift += VARINT_BITS_PER_BYTE
        }
        throw ProtocolException("Payload length is longer than $MAX_VARINT_BYTES bytes")
    }
}
