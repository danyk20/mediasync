/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

/** A snapshot of a running transfer, for progress UI. Totals grow as further batches are offered. */
data class TransferProgress(
    val itemsDone: Int,
    val itemsTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentItem: String?,
)

/** Called often (every chunk); implementations should be cheap and throttle UI updates themselves. */
fun interface ProgressListener {
    fun onProgress(progress: TransferProgress)
}

/**
 * How fast a transfer goes, and how long the rest takes at that speed: [remainingMillis] is `null` while nothing
 * moves (a stalled link), and only means something where the whole total is known (the sending phone's).
 */
data class TransferEstimate(val bytesPerSecond: Long, val remainingMillis: Long?) {
    /**
     * The time left in whole minutes, as people say it: 0 under a minute; otherwise rounded up, so "about 2 min" never
     * means 2:50; from an hour on, rounded up to 5 minutes. `null` while it isn't known.
     */
    fun minutesLeft(): Int? = remainingMillis?.let { millis ->
        if (millis < MILLIS_PER_MINUTE) return@let 0
        val minutes = ((millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
        if (minutes < MINUTES_PER_HOUR) minutes else (minutes + HOUR_STEP - 1) / HOUR_STEP * HOUR_STEP
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MINUTES_PER_HOUR = 60
        const val HOUR_STEP = 5
    }
}

/**
 * Estimates a transfer's speed from its progress over the last [windowMillis], so it doesn't jump with every file,
 * and only after [warmUpMillis], when there's enough to go on. Feed it every progress update, and [refresh] it now
 * and then: progress stops coming while a link stalls, and the speed should fall with it.
 */
class TransferEstimator(
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val warmUpMillis: Long = WARM_UP_MILLIS,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // Time, bytes done.
    private var started = 0L
    private var last: TransferProgress? = null

    @Synchronized
    fun update(progress: TransferProgress): TransferEstimate? {
        val time = now()
        // Fewer bytes than before: another transfer started.
        if (samples.isEmpty() || progress.bytesDone < samples.last().second) {
            samples.clear()
            started = time
        }
        last = progress
        samples.addLast(time to progress.bytesDone)
        // Keeps one sample from before the window, so the speed covers all of it.
        while (samples.size > 2 && samples[1].first <= time - windowMillis) samples.removeFirst()
        val (since, bytesThen) = samples.first()
        if (time - started < warmUpMillis || time <= since) return null
        val bytesPerSecond = (progress.bytesDone - bytesThen) * MILLIS_PER_SECOND / (time - since)
        val left = (progress.bytesTotal - progress.bytesDone).coerceAtLeast(0)
        return TransferEstimate(
            bytesPerSecond,
            if (bytesPerSecond >
                0
            ) {
                left * MILLIS_PER_SECOND / bytesPerSecond
            } else {
                null
            },
        )
    }

    /** The estimate now, from the last progress: lower the longer nothing has come. `null` before any progress. */
    @Synchronized
    fun refresh(): TransferEstimate? = last?.let(::update)

    private companion object {
        const val WINDOW_MILLIS = 10_000L
        const val WARM_UP_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
