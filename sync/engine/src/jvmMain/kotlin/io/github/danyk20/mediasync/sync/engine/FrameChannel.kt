/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.ControlMessage
import io.github.danyk20.mediasync.sync.protocol.Frame
import io.github.danyk20.mediasync.sync.protocol.FrameCodec
import io.github.danyk20.mediasync.sync.protocol.FrameHeader
import io.github.danyk20.mediasync.sync.protocol.FrameType
import io.github.danyk20.mediasync.sync.protocol.MessageCodec
import io.github.danyk20.mediasync.sync.protocol.ProtocolException
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.IOException
import okio.EOFException

/** The peer closed the session, either with `Bye` ([byeReason]) or by closing the stream (`null`). */
class PeerEndedSessionException(val byeReason: Int?) :
    IOException(if (byeReason == null) "Peer closed the connection" else "Peer ended the session (reason $byeReason)")

/** The peer speaks a protocol version or plays a role this device can't work with. */
class IncompatiblePeerException(message: String) : ProtocolException(message)

/** Typed message I/O over a [PeerConnection]. Not thread-safe: one session uses one channel. */
internal class FrameChannel(connection: PeerConnection) {
    private val source = connection.source
    private val sink = connection.sink

    fun send(message: ControlMessage) {
        FrameCodec.write(MessageCodec.encode(message), sink)
        sink.flush()
    }

    /** Queues a `DATA` frame; it's flushed with the next [send]. */
    fun sendData(bytes: ByteArray, byteCount: Int) {
        FrameCodec.writeHeader(FrameHeader(FrameType.DATA, byteCount), sink)
        sink.write(bytes, 0, byteCount)
    }

    fun nextHeader(): FrameHeader = FrameCodec.readHeader(source) ?: throw PeerEndedSessionException(null)

    /** Decodes a control frame whose header was just read; `null` for unknown types, which are skipped. */
    fun readControl(header: FrameHeader): ControlMessage? {
        val payload = try {
            source.readByteString(header.payloadLength.toLong())
        } catch (e: EOFException) {
            throw ProtocolException("Stream ended inside a ${header.payloadLength} byte payload", e)
        }
        return MessageCodec.decode(Frame(header.type, payload))
    }

    /** Reads a `DATA` payload whose header was just read into [into]; returns its length. */
    fun readData(header: FrameHeader, into: ByteArray): Int {
        val length = header.payloadLength
        if (length >
            into.size
        ) {
            throw ProtocolException("DATA frame of $length bytes exceeds the ${into.size} byte chunk")
        }
        var read = 0
        while (read < length) {
            val n = source.read(into, read, length - read)
            if (n == -1) throw ProtocolException("Stream ended inside a DATA frame")
            read += n
        }
        return length
    }

    /** The next control message, skipping unknown types. `DATA` is a protocol error here. */
    fun receive(): ControlMessage {
        while (true) {
            val header = nextHeader()
            if (header.type == FrameType.DATA) throw ProtocolException("Unexpected DATA frame")
            readControl(header)?.let { return it }
        }
    }

    /** The next message, which must be a [T]. A `Bye` ends the session with [PeerEndedSessionException]. */
    inline fun <reified T : ControlMessage> expect(): T = when (val message = receive()) {
        is T -> message
        is Bye -> throw PeerEndedSessionException(message.reason)
        else -> throw ProtocolException("Expected ${T::class.simpleName}, got ${message::class.simpleName}")
    }
}

internal const val CHUNK_SIZE: Int = 1024 * 1024

/** Small enough that parallel connections share the work; a round trip per batch is negligible with pipelining. */
internal const val OFFER_BATCH_SIZE: Int = 64

/** A batch also ends at this size, so one connection doesn't claim every big video before another joins. */
internal const val OFFER_BATCH_BYTES: Long = 32L * 1024 * 1024
