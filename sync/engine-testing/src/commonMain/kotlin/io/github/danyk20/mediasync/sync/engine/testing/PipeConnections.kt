/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.Sink
import okio.Timeout
import okio.buffer

/** Deterministic device ids for tests. */
fun deviceId(seed: Int): DeviceId = DeviceId.of(ByteArray(32) { (seed * 31 + it).toByte() })

/**
 * Faults injected into the producer → consumer direction, counted in bytes on the wire.
 *
 * @property cutAfter close both directions after this many bytes, like a dropped Wi-Fi connection.
 * @property flipByteAt invert the bits of the byte at this position, like corruption that TLS missed.
 */
data class Faults(val cutAfter: Long? = null, val flipByteAt: Long? = null)

/**
 * Two ends of an in-memory, bidirectional connection between a producer and a consumer.
 *
 * Reads and writes time out after [timeoutMillis], like the socket timeouts of the real transport, so a
 * session stalled by a corrupted frame header fails instead of hanging.
 */
class ConnectionPair(
    producerId: DeviceId,
    consumerId: DeviceId,
    faults: Faults = Faults(),
    timeoutMillis: Long = 10_000,
    /** Each byte arrives this long after it was written, like a Wi-Fi hop (a round trip is twice this). */
    latencyMillis: Long = 0,
) {
    private val toConsumer = newTestPipe(PIPE_BUFFER, timeoutMillis)
    private val toProducer = newTestPipe(PIPE_BUFFER, timeoutMillis)

    /** What the producer writes, as it reaches the consumer. */
    private val toConsumerSink = SocketLikeSink(toConsumer.sink).delayed(latencyMillis)

    /** What the consumer writes, as it reaches the producer. */
    private val toProducerSink = SocketLikeSink(toProducer.sink).delayed(latencyMillis)

    /** The producer's end; its [PeerConnection.peerId] is the consumer, as TLS would have authenticated. */
    val producerEnd: PeerConnection = End(
        peerId = consumerId,
        source = toProducer.source.buffer(),
        sink = FaultySink(toConsumerSink, faults) { endOf(toConsumerSink, toProducer) }.buffer(),
        onClose = { endOf(toConsumerSink, toProducer) },
    )

    /** The consumer's end; its [PeerConnection.peerId] is the producer. */
    val consumerEnd: PeerConnection = End(
        peerId = producerId,
        source = toConsumer.source.buffer(),
        sink = toProducerSink.buffer(),
        onClose = { endOf(toProducerSink, toConsumer) },
    )

    /**
     * One side's end of the connection, as with a socket, whether it closed it or the connection dropped: the other
     * side still reads what was sent before, then the end, and this side reads nothing more. Cancelling the outgoing
     * pipe instead would throw away what the other side hadn't read yet: on a slow machine, everything.
     */
    private fun endOf(outgoing: Sink, incoming: TestPipe) {
        runCatching { outgoing.close() } // After what's still on its way.
        incoming.cancel()
    }

    private class End(
        override val peerId: DeviceId,
        override val source: BufferedSource,
        override val sink: BufferedSink,
        private val onClose: () -> Unit,
    ) : PeerConnection {
        override val remoteAddress = "memory"

        override fun close() = onClose()
    }

    /**
     * Fails like a socket's: with an [IOException] once this side closed it (a pipe throws IllegalStateException),
     * and a flush doesn't fail because the other side closed after reading what was sent (a cancelled pipe's would).
     * A write after the other side closed still fails, as a socket's would.
     */
    private class SocketLikeSink(private val delegate: Sink) : Sink by delegate {
        override fun write(source: Buffer, byteCount: Long) = whileOpen { delegate.write(source, byteCount) }

        override fun flush() = whileOpen {
            try {
                delegate.flush()
            } catch (_: IOException) {
                // The other side closed: what it didn't read is lost, as over a network.
            }
        }

        private inline fun whileOpen(block: () -> Unit) = try {
            block()
        } catch (e: IllegalStateException) {
            throw IOException("Socket closed", e)
        }
    }

    private class FaultySink(private val delegate: Sink, private val faults: Faults, private val onCut: () -> Unit) :
        Sink by delegate {
        private var position = 0L

        override fun write(source: Buffer, byteCount: Long) {
            val chunk = Buffer()
            source.read(chunk, byteCount)
            val flip = faults.flipByteAt
            if (flip != null && flip in position until position + byteCount) {
                val bytes = chunk.readByteArray()
                val index = (flip - position).toInt()
                bytes[index] = bytes[index].toInt().inv().toByte()
                chunk.write(bytes)
            }
            val cut = faults.cutAfter
            if (cut != null && position + byteCount > cut) {
                val allowed = cut - position
                if (allowed > 0) delegate.write(chunk, allowed)
                position = cut
                onCut()
                throw IOException("Connection dropped (injected)")
            }
            position += byteCount
            delegate.write(chunk, byteCount)
        }
    }

    /** Delivers what's written [latencyMillis] later, without holding up the writer. */
    private class DelayedSink(private val delegate: Sink, private val latencyMillis: Long) : Sink {
        private val scope = CoroutineScope(SupervisorJob() + blockingIo)
        private val queue = Channel<Pair<Long, Buffer>>(Channel.UNLIMITED)
        private val delivery = scope.launch {
            try {
                for ((due, chunk) in queue) {
                    val wait = due - nowMillis()
                    if (wait > 0) delay(wait)
                    delegate.write(chunk, chunk.size)
                    delegate.flush()
                }
            } catch (_: IOException) {
                // The pipe was cancelled.
            }
        }

        override fun write(source: Buffer, byteCount: Long) {
            val chunk = Buffer()
            source.read(chunk, byteCount)
            queue.trySend(nowMillis() + latencyMillis to chunk)
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            queue.close() // What's queued still goes out, in order.
            runBlocking { delivery.join() }
            delegate.close()
            scope.cancel()
        }
    }

    private fun Sink.delayed(latencyMillis: Long): Sink = if (latencyMillis > 0) {
        DelayedSink(this, latencyMillis)
    } else {
        this
    }

    private companion object {
        const val PIPE_BUFFER = 4L * 1024 * 1024
    }
}

/**
 * Runs both sides of a session concurrently, like two phones. Each result is the side's return value or
 * the exception it ended with.
 */
fun <P, C> runBothSides(producer: () -> P, consumer: () -> C): Pair<Result<P>, Result<C>> {
    val scope = CoroutineScope(SupervisorJob() + blockingIo)
    try {
        val consumerSide = scope.async { runCatching { consumer() } }
        val producerSide = scope.async { runCatching { producer() } }
        return producerSide.outcome() to consumerSide.outcome()
    } finally {
        scope.cancel()
    }
}

/** The side's result, or a failure once [TIMEOUT_MILLIS] passed: the side is then cancelled. */
private fun <T> Deferred<Result<T>>.outcome(): Result<T> = runBlocking {
    withTimeoutOrNull(TIMEOUT_MILLIS) { await() } ?: run {
        this@outcome.cancel()
        Result.failure(IllegalStateException("No result within $TIMEOUT_MILLIS ms"))
    }
}

private const val TIMEOUT_MILLIS = 30_000L

/** Monotonic milliseconds, for the latency of [DelayedSink]. */
private val clockOrigin = TimeSource.Monotonic.markNow()

private fun nowMillis(): Long = clockOrigin.elapsedNow().inWholeMilliseconds
