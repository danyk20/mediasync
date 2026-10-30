/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * A snapshot of a running transfer, for progress UI. Totals grow as further batches are offered. [bytesSkipped]: of
 * [bytesDone], what didn't cross the link: the start of a resumed file, which the other phone already kept, and files
 * that weren't sent after all (the other phone had them, or they couldn't be read). It's done, but it isn't speed.
 */
data class TransferProgress(
    val itemsDone: Int,
    val itemsTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentItem: String?,
    val bytesSkipped: Long = 0,
) {
    /** The bytes that actually crossed the link. */
    val bytesMoved: Long get() = bytesDone - bytesSkipped
}

/** Called often (every chunk); implementations should be cheap and throttle UI updates themselves. */
fun interface ProgressListener {
    fun onProgress(progress: TransferProgress)
}

/**
 * How fast a transfer goes now, and how long the rest takes: [remainingMillis] is `null` while nothing moves (a stalled
 * link), and only means something where the whole total is known (the sending phone's).
 */
data class TransferEstimate(val bytesPerSecond: Long, val remainingMillis: Long?) {
    /**
     * The time left in seconds, as people say it: under a minute in steps of 10 seconds, then in whole minutes, and
     * from an hour on in steps of 5 minutes. Always rounded up, so "about 2 min" never means 2:50, and the last seconds
     * never read 0. `null` while it isn't known.
     */
    fun secondsLeft(): Int? = remainingMillis?.let { millis ->
        val seconds = maxOf((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND, 1)
        val step = when {
            seconds < SECONDS_PER_MINUTE -> SECONDS_STEP
            seconds <= SECONDS_PER_HOUR -> SECONDS_PER_MINUTE
            else -> HOURS_STEP
        }
        ((seconds + step - 1) / step * step).toInt()
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
        const val SECONDS_STEP = 10L
        const val HOURS_STEP = 5 * SECONDS_PER_MINUTE
    }
}

/** The speed [atMillis] after a transfer started, for its chart. */
data class SpeedSample(val atMillis: Long, val bytesPerSecond: Long)

/**
 * Estimates a transfer's speed and the time left. Feed it every progress update, and [refresh] it now and then:
 * progress stops coming while a link stalls, and the speed should fall with it. [reset] it when another transfer
 * starts. Neither is known in the first [warmUpMillis], when there's too little to go on.
 *
 * The speed is the one now: over the last [windowMillis], so it follows the link without jumping with every file. The
 * time left would swing with it, so it divides the bytes left by a steadier speed of its own: an average in which the
 * latest seconds count most and older ones fade, looking back about half as far as the time left looks ahead (between
 * 20 seconds and 3 minutes, as a long transfer is better told by its last minutes than by its last moments), then
 * smoothed once more over about 30 seconds. While the link holds, the time left counts down a second a second; when
 * it changes for good, the time left follows within a minute or so instead of jumping. Only the speed is smoothed:
 * bytes that turn out not to need sending ([TransferProgress.bytesSkipped]) come off the time left at once.
 */
class TransferEstimator(
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val warmUpMillis: Long = WARM_UP_MILLIS,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // Time, bytes moved.
    private var started = 0L
    private var last: TransferProgress? = null
    private val speeds = ArrayDeque<SpeedSample>()

    // The time left's speed, in bytes a millisecond: the bytes moved and the time they took, both fading as they age;
    // then their ratio smoothed once more, last at steadyAt.
    private var fadingBytes = 0.0
    private var fadingMillis = 0.0
    private var steadySpeed: Double? = null
    private var steadyAt = 0L
    private var lastLeftMillis: Double? = null

    /** The speed every second of this transfer so far (the last 6 hours of a longer one), for its chart. */
    @Synchronized
    fun history(): List<SpeedSample> = speeds.toList()

    /** Another transfer starts: the speed warms up again, and its chart starts empty. */
    @Synchronized
    fun reset() {
        samples.clear()
        speeds.clear()
        last = null
        fadingBytes = 0.0
        fadingMillis = 0.0
        steadySpeed = null
        lastLeftMillis = null
    }

    /**
     * The estimate with [progress] in. Progress comes from several threads (parallel connections, and each one's
     * confirmations), so it may come out of order: one with fewer bytes than already known is late, and changes
     * nothing.
     */
    @Synchronized
    fun update(progress: TransferProgress): TransferEstimate? {
        val latest = last?.takeIf { progress.bytesDone < it.bytesDone } ?: progress
        last = latest
        return estimate(now(), latest)
    }

    /** The estimate now, from the last progress: lower the longer nothing has come. `null` before any progress. */
    @Synchronized
    fun refresh(): TransferEstimate? = last?.let { estimate(now(), it) }

    private fun estimate(time: Long, progress: TransferProgress): TransferEstimate? {
        val moved = progress.bytesMoved
        samples.lastOrNull()?.let { (then, movedThen) -> fade((time - then).coerceAtLeast(0), moved - movedThen) }
        if (samples.isEmpty()) started = time
        samples.addLast(time to moved)
        // Keeps one sample from before the window, so the speed covers all of it.
        while (samples.size > 2 && samples[1].first <= time - windowMillis) samples.removeFirst()
        val (since, movedThen) = samples.first()
        if (time - started < warmUpMillis || time <= since) return null
        val bytesPerSecond = (moved - movedThen) * MILLIS_PER_SECOND / (time - since)
        record(time - started, bytesPerSecond)
        val left = timeLeft(time, (progress.bytesTotal - progress.bytesDone).coerceAtLeast(0))
        return TransferEstimate(bytesPerSecond, left.takeIf { bytesPerSecond > 0 })
    }

    /** Adds [bytes] moved over the last [millis] to the time left's speed, fading what came before. */
    private fun fade(millis: Long, bytes: Long) {
        val lookBack = lastLeftMillis?.let { (it / 2).coerceIn(MIN_LOOK_BACK_MILLIS, MAX_LOOK_BACK_MILLIS) }
            ?: FIRST_LOOK_BACK_MILLIS
        val kept = exp(-millis / lookBack)
        fadingBytes = fadingBytes * kept + bytes.coerceAtLeast(0)
        fadingMillis = fadingMillis * kept + millis
    }

    /** How long the [left] bytes take at the steady speed; `null` while nothing has moved yet. */
    private fun timeLeft(time: Long, left: Long): Long? {
        if (fadingBytes <= 0.0) return null
        val speed = fadingBytes / fadingMillis
        val steady = steadySpeed?.let { previous ->
            previous + (speed - previous) * (1 - exp(-(time - steadyAt) / SMOOTHING_MILLIS))
        } ?: speed
        steadySpeed = steady
        steadyAt = time
        val millis = left / steady
        lastLeftMillis = millis
        return millis.roundToLong()
    }

    /** One sample a second: progress comes far more often. */
    private fun record(atMillis: Long, bytesPerSecond: Long) {
        if (speeds.isNotEmpty() && atMillis - speeds.last().atMillis < SAMPLE_MILLIS) return
        speeds.addLast(SpeedSample(atMillis, bytesPerSecond))
        if (speeds.size > MAX_SAMPLES) speeds.removeFirst()
    }

    private companion object {
        const val WINDOW_MILLIS = 10_000L
        const val WARM_UP_MILLIS = 5_000L
        const val FIRST_LOOK_BACK_MILLIS = 60_000.0
        const val MIN_LOOK_BACK_MILLIS = 20_000.0
        const val MAX_LOOK_BACK_MILLIS = 180_000.0
        const val SMOOTHING_MILLIS = 30_000.0
        const val SAMPLE_MILLIS = 1_000L
        const val MAX_SAMPLES = 6 * 60 * 60 // Six hours, a second each.
    }
}

private const val MILLIS_PER_SECOND = 1_000L
