/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

/** A channel of whole messages, such as a WebRTC data channel (PLAN §8.4). */
interface MessageChannel {
    /** Queues [message]; `false` once the channel is closing. */
    fun send(message: ByteArray): Boolean

    /** Bytes queued and not yet sent: the writer waits while there are too many. */
    fun bufferedAmount(): Long

    fun close()
}

/**
 * The byte streams the sync engine reads and writes, over a [MessageChannel], as over TCP: writes go out in messages of
 * at most [maxMessageBytes], waiting while [highWaterBytes] are queued; received messages are read in order.
 *
 * The channel's callbacks feed it: [onMessage], [onBufferedAmountChange] and [onClosed].
 */
class ChannelStream(
    private val channel: MessageChannel,
    private val maxMessageBytes: Int = MAX_MESSAGE_BYTES,
    private val highWaterBytes: Long = HIGH_WATER_BYTES,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val incoming = Buffer()
    private var closed = false
    private var failure: IOException? = null

    fun onMessage(message: ByteArray) = lock.withLock {
        incoming.write(message)
        changed.signalAll()
    }

    /** The channel sent some of what was queued: a waiting writer may go on. */
    fun onBufferedAmountChange() = lock.withLock { changed.signalAll() }

    /** The channel closed; with [error] when it failed. Reads end after what arrived before. */
    fun onClosed(error: IOException? = null) = lock.withLock {
        closed = true
        failure = error
        changed.signalAll()
    }

    val source: BufferedSource = Incoming().buffer()

    val sink: BufferedSink = Outgoing().buffer()

    fun close() {
        onClosed()
        channel.close()
    }

    private inner class Incoming : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (incoming.size == 0L) {
                if (closed) failure?.let { throw it } ?: return -1
                val left = deadline - System.nanoTime()
                if (left <= 0) throw SocketTimeoutException("Nothing from the other phone for $timeoutMillis ms")
                changed.awaitNanos(left)
            }
            incoming.read(sink, minOf(byteCount, incoming.size))
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = this@ChannelStream.close()
    }

    private inner class Outgoing : Sink {
        private val pending = Buffer()

        override fun write(source: Buffer, byteCount: Long) {
            pending.write(source, byteCount)
            while (pending.size >= maxMessageBytes) send(maxMessageBytes.toLong())
        }

        override fun flush() {
            if (pending.size > 0) send(pending.size)
        }

        private fun send(byteCount: Long) {
            awaitRoom()
            if (!channel.send(pending.readByteArray(byteCount))) throw IOException("The connection closed")
        }

        /** Waits while too much is queued; checks again now and then, in case a callback never comes. */
        private fun awaitRoom() = lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!closed && channel.bufferedAmount() > highWaterBytes) {
                val left = deadline - System.nanoTime()
                if (left <= 0) throw SocketTimeoutException("The other phone took nothing for $timeoutMillis ms")
                changed.awaitNanos(minOf(left, TimeUnit.MILLISECONDS.toNanos(RECHECK_MILLIS)))
            }
            if (closed) throw failure ?: IOException("The connection closed")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = this@ChannelStream.close()
    }

    private companion object {
        /** Well under the 256 KiB WebRTC data channels deliver in one message everywhere. */
        const val MAX_MESSAGE_BYTES = 64 * 1024
        const val HIGH_WATER_BYTES = 4L * 1024 * 1024
        const val TIMEOUT_MILLIS = 60_000L
        const val RECHECK_MILLIS = 50L
    }
}

/** A connection to the paired phone over a [ChannelStream]; the pair's mailbox vouches for [peerId] (ADR 0009). */
class ChannelPeerConnection(
    override val peerId: DeviceId,
    private val stream: ChannelStream,
    override val remoteAddress: String,
) : PeerConnection {
    override val source: BufferedSource get() = stream.source
    override val sink: BufferedSink get() = stream.sink

    override fun close() = stream.close()
}
