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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.Pipe
import okio.Sink
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
) {
    private val toConsumer = Pipe(PIPE_BUFFER)
    private val toProducer = Pipe(PIPE_BUFFER)

    init {
        for (pipe in listOf(toConsumer, toProducer)) {
            pipe.source.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
            pipe.sink.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    /** The producer's end; its [PeerConnection.peerId] is the consumer, as TLS would have authenticated. */
    val producerEnd: PeerConnection = End(
        peerId = consumerId,
        source = toProducer.source.buffer(),
        sink = FaultySink(toConsumer.sink, faults) { closeAll() }.buffer(),
    )

    /** The consumer's end; its [PeerConnection.peerId] is the producer. */
    val consumerEnd: PeerConnection = End(
        peerId = producerId,
        source = toConsumer.source.buffer(),
        sink = toProducer.sink.buffer(),
    )

    private fun closeAll() {
        toConsumer.cancel()
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

    private companion object {
        const val PIPE_BUFFER = 4L * 1024 * 1024
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
