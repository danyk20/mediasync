/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps requests under the service's declared rate ([ConnectorLimits.requestsPerSecond]) with a token bucket: up to
 * [burst] requests at once, then one per interval. A request that would go over waits its turn.
 */
class RateLimitedConnector(
    override val inner: BackupConnector,
    requestsPerSecond: Double,
    burst: Int = ceil(requestsPerSecond).toInt().coerceAtLeast(1),
    nanoTime: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DecoratedConnector,
    BackupConnector by inner {

    private val bucket = TokenBucket(requestsPerSecond, burst, nanoTime)

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult {
        bucket.take(sleep)
        return inner.upload(item, progress)
    }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> {
        bucket.take(sleep)
        return inner.verify(receipts)
    }

    override suspend fun lookup(ids: List<ContentId>): Map<ContentId, String> {
        bucket.take(sleep)
        return inner.lookup(ids)
    }
}

/** [capacity] tokens, refilled at [perSecond]; each request takes one. Waiters queue up in order. */
internal class TokenBucket(private val perSecond: Double, private val capacity: Int, private val nanoTime: () -> Long) {
    init {
        require(perSecond > 0 && capacity >= 1) { "Rate $perSecond/s, burst $capacity" }
    }

    private val mutex = Mutex()
    private var tokens = capacity.toDouble()
    private var refilledAt = nanoTime()

    suspend fun take(sleep: suspend (Long) -> Unit) = mutex.withLock {
        refill()
        if (tokens < 1) {
            sleep(ceil((1 - tokens) / perSecond * MILLIS_PER_SECOND).toLong())
            refill()
        }
        tokens -= 1
    }

    private fun refill() {
        val now = nanoTime()
        tokens = minOf(capacity.toDouble(), tokens + (now - refilledAt) / NANOS_PER_SECOND * perSecond)
        refilledAt = now
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
