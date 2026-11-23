/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_content_context_t
import platform.Network.nw_error_get_error_code
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_t

private const val MAX_RECEIVE_BYTES = 65_536
private const val MAX_SEND_BYTES = 1 shl 20

/**
 * A [PeerConnection] over a Network.framework connection that has finished its handshake. The okio streams block the
 * calling thread, as the JVM ones do; each receive and send waits on a one-shot latch with the timeouts from
 * [AppleLanTimeouts]. One receive and one send are in flight at a time.
 */
internal class NwPeerConnection(
    private val connection: nw_connection_t,
    private val queue: dispatch_queue_t,
    override val peerId: DeviceId,
    override val remoteAddress: String,
    private val timeouts: AppleLanTimeouts,
) : PeerConnection {
    private val context: nw_content_context_t =
        nw_content_context_create("mediasync") ?: error("nw_content_context_create")
    private var closed = false

    override val source: BufferedSource = NwSource().buffer()
    override val sink: BufferedSink = NwSink().buffer()

    override fun close() {
        if (closed) return
        closed = true
        nw_connection_cancel(connection)
    }

    /** One receive at a time; returns the next bytes, or an empty array with [Reply.complete] at end of stream. */
    private fun receive(): Reply {
        val reply = OneShot<Reply>()
        nw_connection_receive(connection, 1u, MAX_RECEIVE_BYTES.toUInt()) { content, _, isComplete, error ->
            reply.offer(
                Reply(
                    bytes = content?.toByteArray() ?: ByteArray(0),
                    complete = isComplete,
                    errorCode = error?.let { nw_error_get_error_code(it) },
                ),
            )
        }
        val result = reply.await(timeouts.readMillis) ?: run {
            nw_connection_cancel(connection)
            throw IOException("Read timed out after ${timeouts.readMillis} ms")
        }
        result.errorCode?.let { throw IOException("Read failed with error $it") }
        return result
    }

    private fun send(bytes: ByteArray) {
        val data = bytes.toDispatchData(queue)
        val done = OneShot<Reply>()
        nw_connection_send(connection, data, context, true) { error ->
            done.offer(
                Reply(
                    bytes = ByteArray(0),
                    complete = true,
                    errorCode = error?.let {
                        nw_error_get_error_code(it)
                    },
                ),
            )
        }
        val result = done.await(timeouts.readMillis) ?: run {
            nw_connection_cancel(connection)
            throw IOException("Write timed out after ${timeouts.readMillis} ms")
        }
        result.errorCode?.let { throw IOException("Write failed with error $it") }
    }

    private inner class NwSource : Source {
        private var pending = ByteArray(0)
        private var position = 0
        private var endOfStream = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0) { "byteCount < 0: $byteCount" }
            if (byteCount == 0L) return 0L
            while (position == pending.size) {
                if (endOfStream) return -1L
                val reply = receive()
                pending = reply.bytes
                position = 0
                if (reply.complete) endOfStream = true
            }
            val count = minOf(byteCount, (pending.size - position).toLong()).toInt()
            sink.write(pending, position, count)
            position += count
            return count.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = this@NwPeerConnection.close()
    }

    private inner class NwSink : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            var remaining = byteCount
            while (remaining > 0) {
                val count = minOf(remaining, MAX_SEND_BYTES.toLong()).toInt()
                send(source.readByteArray(count.toLong()))
                remaining -= count
            }
        }

        // Each write waits for its send to complete, so there is nothing buffered to flush.
        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = this@NwPeerConnection.close()
    }

    private class Reply(val bytes: ByteArray, val complete: Boolean, val errorCode: Int?)
}

/** The bytes of a dispatch_data, copied out. */
private fun dispatch_data_t.toByteArray(): ByteArray {
    val parts = ArrayList<ByteArray>()
    dispatch_data_apply(this) { _, _, buffer, size ->
        if (buffer != null) parts.add(buffer.reinterpret<ByteVar>().readBytes(size.toInt()))
        true
    }
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

/** A dispatch_data copy of these bytes (the default destructor copies), so the Kotlin array can be reused. */
private fun ByteArray.toDispatchData(queue: dispatch_queue_t): dispatch_data_t = usePinned { pinned ->
    dispatch_data_create(pinned.addressOf(0), size.toULong(), queue, null)
}
