/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.Pipe
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
    private val toConsumer = Pipe(PIPE_BUFFER)
    private val toProducer = Pipe(PIPE_BUFFER)

    init {
        for (pipe in listOf(toConsumer, toProducer)) {
            pipe.source.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
            pipe.sink.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    /** What the producer writes, as it reaches the consumer. */
    private val toConsumerSink = toConsumer.sink.delayed(latencyMillis)

    /** The producer's end; its [PeerConnection.peerId] is the consumer, as TLS would have authenticated. */
    val producerEnd: PeerConnection = End(
        peerId = consumerId,
        source = toProducer.source.buffer(),
        sink = FaultySink(toConsumerSink, faults) { drop() }.buffer(),
    )

    /** The consumer's end; its [PeerConnection.peerId] is the producer. */
    val consumerEnd: PeerConnection = End(
        peerId = producerId,
        source = toConsumer.source.buffer(),
        sink = toProducer.sink.delayed(latencyMillis).buffer(),
    )

    private fun closeAll() {
        toConsumer.cancel()
        toProducer.cancel()
    }

    /**
     * The connection drops: the consumer still reads what was sent before, then the end, as from a socket. Cancelling
     * that pipe instead would throw away what it hadn't read yet: on a slow machine, everything.
     */
    private fun drop() {
        toConsumerSink.close() // After what's still on its way.
        toProducer.cancel()
    }

    private inner class End(
        override val peerId: DeviceId,
        override val source: BufferedSource,
        override val sink: BufferedSink,
    ) : PeerConnection {
        override val remoteAddress = "memory"

        override fun close() = closeAll()
    }

    private class FaultySink(delegate: Sink, private val faults: Faults, private val onCut: () -> Unit) :
        ForwardingSink(delegate) {
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
                if (allowed > 0) super.write(chunk, allowed)
                position = cut
                onCut()
                throw IOException("Connection dropped (injected)")
            }
            position += byteCount
            super.write(chunk, byteCount)
        }
    }

    /** Delivers what's written [latencyMillis] later, without holding up the writer. */
    private class DelayedSink(private val delegate: Sink, private val latencyMillis: Long) : Sink {
        private val queue = LinkedBlockingQueue<Pair<Long, Buffer>>()
        private val delivery = thread(isDaemon = true, name = "latency") {
            try {
                while (true) {
                    val (due, chunk) = queue.take()
                    if (chunk.size == 0L) break // Closed.
                    val wait = due - System.nanoTime() / NANOS_PER_MILLI
                    if (wait > 0) Thread.sleep(wait)
                    delegate.write(chunk, chunk.size)
                    delegate.flush()
                }
            } catch (_: IOException) {
                // The pipe was cancelled.
            } catch (_: InterruptedException) {
                // Shut down.
            }
        }

        override fun write(source: Buffer, byteCount: Long) {
            val chunk = Buffer()
            source.read(chunk, byteCount)
            queue.put(System.nanoTime() / NANOS_PER_MILLI + latencyMillis to chunk)
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            queue.put(0L to Buffer())
            delivery.join()
            delegate.close()
        }
    }

    private fun Sink.delayed(latencyMillis: Long): Sink = if (latencyMillis >
        0
    ) {
        DelayedSink(this, latencyMillis)
    } else {
        this
    }

    private companion object {
        const val PIPE_BUFFER = 4L * 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Runs both sides of a session concurrently, like two phones. Each result is the side's return value or
 * the exception it ended with.
 */
fun <P, C> runBothSides(producer: () -> P, consumer: () -> C): Pair<Result<P>, Result<C>> {
    val executor = Executors.newFixedThreadPool(2)
    try {
        val consumerFuture = executor.submit(Callable { consumer() })
        val producerFuture = executor.submit(Callable { producer() })
        return producerFuture.outcome() to consumerFuture.outcome()
    } finally {
        executor.shutdownNow()
    }
}

private fun <T> java.util.concurrent.Future<T>.outcome(): Result<T> = try {
    Result.success(get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
} catch (e: ExecutionException) {
    Result.failure(e.cause ?: e)
} catch (e: TimeoutException) {
    cancel(true)
    Result.failure(e)
}

private const val TIMEOUT_SECONDS = 30L
