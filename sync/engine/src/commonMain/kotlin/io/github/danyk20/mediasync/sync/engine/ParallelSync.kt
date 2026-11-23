/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import okio.use

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
 * @param holdBack what isn't sent over this connection, such as videos over the relay (PLAN §17, relay saver): it's
 *   counted in the result and sent another time.
 */
class ParallelSync(
    private val session: (ProgressListener) -> ProducerSession,
    private val connect: () -> PeerConnection?,
    private val progress: ProgressListener = ProgressListener {},
    private val maxStreams: Int = MAX_STREAMS,
    private val mayAddStream: () -> Boolean = { true },
    private val probeMillis: Long = PROBE_MILLIS,
    private val holdBack: (SourceItem) -> Boolean = { false },
) {
    /** Guarded by [latestLock]: the newest progress of each connection. */
    private val latestLock = Lock()
    private val latest = HashMap<Int, TransferProgress>()

    /** Serialises the reports to [progress], so one connection's report never interleaves with another's. */
    private val reportLock = Lock()

    /** One file shows at a time, though several connections send at once. */
    private val follower = SendingFollower()

    /**
     * Sends over [first] and, when it pays, over more connections. Blocks the calling thread until every stream ends,
     * so call it from a worker thread, never Main or a single-threaded dispatcher.
     */
    fun sync(first: PeerConnection): SyncResult {
        val probe = session(ProgressListener {})
        val (held, pending) = probe.pendingFor(first.peerId).partition(holdBack)
        // The relay saver never holds the test file back: it's there to test this very connection.
        val queue = WorkQueue(pending, probe.waitingTestFile())
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        try {
            val streams = mutableListOf(start(scope, 0, queue) { session(it).sync(first, queue) })
            grow(scope, queue, streams)
            return merge(streams).copy(heldBack = held.size)
        } finally {
            scope.cancel()
        }
    }

    /** Adds connections while each one added made the whole faster. */
    private fun grow(scope: CoroutineScope, queue: WorkQueue, streams: MutableList<Deferred<Result<SyncResult>>>) {
        var previous: Double? = null
        var lastBytes = 0L
        var growing = true
        while (waitForProbe(streams)) {
            val bytes = latestLock.withLock { latest.values.sumOf { it.bytesMoved } }
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
                    streams += start(scope, streams.size, queue) { connection.use { c -> session(it).sync(c, queue) } }
                }
            }
        }
    }

    private fun mayGrow(streams: List<Deferred<Result<SyncResult>>>, queue: WorkQueue) =
        streams.size < maxStreams && !queue.isEmpty() && !queue.sendingTestFile && mayAddStream()

    private fun megabytes(bytesPerProbe: Double) =
        oneDecimal(bytesPerProbe / BYTES_PER_MEGABYTE / (probeMillis / MILLIS_PER_SECOND))

    /** Waits up to a probe; `false` once every stream has ended. */
    private fun waitForProbe(streams: List<Deferred<Result<SyncResult>>>): Boolean = runBlocking {
        withTimeoutOrNull(probeMillis) { streams.joinAll() } == null
    }

    /**
     * Runs [run] as a coroutine in [scope]. The outcome is a [Result], not a thrown exception: awaiting a failed
     * [Deferred] on the JVM hands back a copy of the exception (stack trace recovery, active under `-ea`), which can
     * lose its fields, such as `PeerEndedSessionException.byeReason`. A [Result] carries the original instance.
     */
    private fun start(
        scope: CoroutineScope,
        index: Int,
        queue: WorkQueue,
        run: (ProgressListener) -> SyncResult,
    ): Deferred<Result<SyncResult>> = scope.async {
        runCatching {
            run { part ->
                // One at a time: the file shown follows one order, whichever connection reported last.
                reportLock.withLock { report(index, part, queue) }
            }
        }
    }

    private fun report(index: Int, part: TransferProgress, queue: WorkQueue) {
        val all = latestLock.withLock {
            latest[index] = part
            latest.values.toList()
        }
        progress.onProgress(
            TransferProgress(
                itemsDone = all.sumOf { it.itemsDone },
                itemsTotal = queue.total,
                bytesDone = all.sumOf { it.bytesDone },
                bytesTotal = queue.totalBytes,
                currentItem = part.currentItem,
                bytesSkipped = all.sumOf { it.bytesSkipped },
                testFile = part.testFile,
                sending = follower.follow(all.map { it.sending }),
            ),
        )
    }

    /** One result for the whole sync; if a connection failed, its failure once every one has ended. */
    private fun merge(streams: List<Deferred<Result<SyncResult>>>): SyncResult {
        val outcomes = runBlocking { streams.awaitAll() }
        outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let {
            // The same message as java.io.IOException(cause) gives: the cause's toString().
            throw it as? IOException ?: IOException(it.toString(), it)
        }
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
                consumerWifiAddresses = a.consumerWifiAddresses ?: b.consumerWifiAddresses,
            )
        }
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val PROBE_MILLIS = 3_000L
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val MILLIS_PER_SECOND = 1000.0

        /** A connection is worth adding while the last one added made the whole at least 10 % faster. */
        const val GAIN = 1.1
    }
}
