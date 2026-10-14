/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * When to try a failed request again: up to [attempts] tries, waiting longer each time (exponential backoff with
 * jitter, so phones don't retry in step), and never longer than [maxDelayMillis] at once.
 */
data class RetryPolicy(val attempts: Int = 4, val baseDelayMillis: Long = 1_000, val maxDelayMillis: Long = 60_000) {
    init {
        require(attempts >= 1) { "attempts: $attempts" }
        require(baseDelayMillis in 1..maxDelayMillis) { "delays: $baseDelayMillis, $maxDelayMillis" }
    }

    /**
     * How long to wait after try number [attempt] failed with [error] before trying again, or `null` to give up: the
     * error isn't worth retrying, the tries are used up, or the service asked for a longer wait than [maxDelayMillis]
     * (the next backup run tries again instead).
     */
    fun delayAfter(attempt: Int, error: ConnectorError, random: Random): Long? {
        if (!error.retryable || attempt >= attempts) return null
        val asked = (error as? ConnectorError.RateLimited)?.retryAfterMillis ?: 0
        if (asked > maxDelayMillis) return null
        val ceiling = (baseDelayMillis shl (attempt - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMillis)
        return maxOf(random.nextLong(ceiling / 2, ceiling + 1), asked)
    }

    private companion object {
        /** Keeps the doubling far from overflowing. */
        const val MAX_SHIFT = 20
    }
}

/** Tries failed uploads again as [policy] says: network trouble, server errors and rate limits usually pass. */
class RetryingConnector(
    override val inner: BackupConnector,
    private val policy: RetryPolicy = RetryPolicy(),
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DecoratedConnector,
    BackupConnector by inner {

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult {
        var attempt = 1
        while (true) {
            val result = inner.upload(item, progress)
            val error = (result as? UploadResult.Failed)?.error ?: return result
            val wait = policy.delayAfter(attempt, error, random) ?: return result
            sleep(wait)
            attempt++
        }
    }
}
