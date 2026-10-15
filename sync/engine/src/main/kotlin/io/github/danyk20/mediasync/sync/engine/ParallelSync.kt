/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Sends over several connections at once when that's faster (docs/PLAN.md §7, parallel streams).
 *
 * Every connection runs an ordinary session; they share one [WorkQueue], so each item goes exactly once. Starting with
 * one connection, another is added after each [probeMillis] while the total speed still rose by more than 10 % with
 * the last one added, up to [maxStreams], and only while [mayAddStream] (a warm phone gets no more). On many Wi-Fis one
 * connection is already as fast as it gets: then no second one is kept adding to.
 *
 * @param session a new session reporting to the given listener.
 * @param connect a new connection to the same consumer, or `null` if there isn't one.
 */
class ParallelSync(
    private val session: (ProgressListener) -> ProducerSession,
    private val connect: () -> PeerConnection?,
    private val progress: ProgressListener = ProgressListener {},
    private val maxStreams: Int = MAX_STREAMS,
    private val mayAddStream: () -> Boolean = { true },
    private val probeMillis: Long = PROBE_MILLIS,
) {
    private val latest = ConcurrentHashMap<Int, TransferProgress>()

    fun sync(first: PeerConnection): SyncResult {
        val queue = WorkQueue(session(ProgressListener {}).pendingFor(first.peerId))
        val pool = Executors.newCachedThreadPool { task -> Thread(task, "mediasync-stream").apply { isDaemon = true } }
        try {
            val streams = mutableListOf(start(pool, 0, queue) { session(it).sync(first, queue) })
            grow(pool, queue, streams)
            return merge(streams)
        } finally {
            pool.shutdown()
        }
    }

    /** Adds connections while each one added made the whole faster. */
    private fun grow(pool: ExecutorService, queue: WorkQueue, streams: MutableList<Future<SyncResult>>) {
        var previous: Double? = null
        var lastBytes = 0L
        var growing = true
        while (waitForProbe(streams)) {
            val bytes = latest.values.sumOf { it.bytesDone }
            val rate = (bytes - lastBytes).toDouble()
            lastBytes = bytes
            if (growing && mayGrow(streams, queue)) {
                val improved = previous?.let { rate > it * GAIN } ?: true
                val connection = if (improved) connect() else null
                growing = connection != null
                if (connection != null) {
                    previous = rate
                    Diagnostics.log(
                        Area.SEND,
                        "Connection ${streams.size + 1} added (${megabytes(rate)} MB/s over the last probe)",
                    )
                    streams += start(pool, streams.size, queue) { connection.use { c -> session(it).sync(c, queue) } }
                }
            }
        }
    }

    private fun mayGrow(streams: List<Future<SyncResult>>, queue: WorkQueue) =
        streams.size < maxStreams && !queue.isEmpty() && mayAddStream()

    private fun megabytes(bytesPerProbe: Double) =
        String.format(Locale.ROOT, "%.1f", bytesPerProbe / BYTES_PER_MEGABYTE / (probeMillis / MILLIS_PER_SECOND))

    /** Waits up to a probe; `false` once every stream has ended. */
    private fun waitForProbe(streams: List<Future<SyncResult>>): Boolean {
        val end = System.nanoTime() + probeMillis * NANOS_PER_MILLI
        while (System.nanoTime() < end) {
            if (streams.all { it.isDone }) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    private fun start(
        pool: ExecutorService,
        index: Int,
        queue: WorkQueue,
        run: (ProgressListener) -> SyncResult,
    ): Future<SyncResult> = pool.submit(
        Callable {
            run { part ->
                latest[index] = part
                val all = latest.values
                progress.onProgress(
                    TransferProgress(
                        itemsDone = all.sumOf { it.itemsDone },
                        itemsTotal = queue.total,
                        bytesDone = all.sumOf { it.bytesDone },
                        bytesTotal = queue.totalBytes,
                        currentItem = part.currentItem,
                    ),
                )
            }
        },
    )

    /** One result for the whole sync; if a connection failed, its failure once every one has ended. */
    private fun merge(streams: List<Future<SyncResult>>): SyncResult {
        val outcomes = streams.map { stream ->
            try {
                Result.success(stream.get())
            } catch (e: ExecutionException) {
                Result.failure(e.cause ?: e)
            }
        }
        outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it as? IOException ?: IOException(it) }
        return outcomes.map { it.getOrThrow() }.reduce { a, b ->
            SyncResult(
                sent = a.sent + b.sent,
                alreadyOnConsumer = a.alreadyOnConsumer + b.alreadyOnConsumer,
                failed = a.failed + b.failed,
                deferred = a.deferred + b.deferred,
                bytesSent = a.bytesSent + b.bytesSent,
                cancelled = a.cancelled || b.cancelled,
                consumerSchedule = a.consumerSchedule ?: b.consumerSchedule,
                withheld = a.withheld + b.withheld,
                consumerTurboKey = a.consumerTurboKey ?: b.consumerTurboKey,
                consumerPairSecret = a.consumerPairSecret ?: b.consumerPairSecret,
                consumerRendezvousUrl = a.consumerRendezvousUrl ?: b.consumerRendezvousUrl,
            )
        }
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val PROBE_MILLIS = 3_000L
        const val POLL_MILLIS = 20L
        const val NANOS_PER_MILLI = 1_000_000L
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val MILLIS_PER_SECOND = 1000.0

        /** A connection is worth adding while the last one added made the whole at least 10 % faster. */
        const val GAIN = 1.1
    }
}
