/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import io.github.danyk20.mediasync.sync.engine.SendingItem
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * How far the file shown is, frame by frame, between the reports that come a few times a second (docs/PLAN.md §3.3,
 * *Sending animation*). From the last report it goes on at the speed of the last reports, or else of the whole
 * transfer, but never more than one report ahead of the last one: the airplane never runs ahead of the bytes. And it
 * never goes back.
 */
internal class FractionPredictor(private val oneReportAtLeast: Long = CHUNK_BYTES) {
    private var size = 0L
    private var bytes = 0L
    private var reportedAt = 0L
    private var bytesPerNano = 0.0
    private var lastStep = 0L
    private var best = 0f

    /** Starts over with [item], as reported [now]. */
    fun reset(item: SendingItem, now: Long) {
        size = item.size
        bytes = item.bytesSent
        reportedAt = now
        bytesPerNano = 0.0
        lastStep = 0
        best = fractionOf(bytes)
    }

    /** The file's latest report, seen [now]: the same as before, or more. */
    fun report(bytesSent: Long, now: Long) {
        if (bytesSent <= bytes) return
        val nanos = now - reportedAt
        if (nanos > 0) bytesPerNano = (bytesSent - bytes).toDouble() / nanos
        lastStep = bytesSent - bytes
        bytes = bytesSent
        reportedAt = now
    }

    /** How far the file is likely to be [now], from 0 to 1. [bytesPerSecond]: the transfer's, if known. */
    fun predict(now: Long, bytesPerSecond: Long?): Float {
        val rate = if (bytesPerNano > 0) bytesPerNano else (bytesPerSecond ?: 0) / NANOS_PER_SECOND
        val ahead = min((now - reportedAt).coerceAtLeast(0) * rate, max(oneReportAtLeast, lastStep).toDouble())
        best = max(best, fractionOf(bytes + ahead.toLong()))
        return best
    }

    private fun fractionOf(sent: Long): Float = if (size <= 0) 1f else (sent.toFloat() / size).coerceIn(0f, 1f)

    private companion object {
        /** What a session sends between two reports at the least: [io.github.danyk20.mediasync.sync.engine]'s chunk. */
        const val CHUNK_BYTES = 1024L * 1024
        const val NANOS_PER_SECOND = 1e9
    }
}

/**
 * Which file the airplane shows, and how far it is, every frame (docs/PLAN.md §3.3): it folds and flies as fast as
 * the file goes over the link, smoothed over [smoothingNanos], but takes at least [minNanos] from the flat sheet to out
 * of sight. A file done sooner plays the rest at that pace; then the next airplane shows the file going over the link
 * at that moment, so with many small files some don't show. A new sheet fades in over [appearNanos].
 */
internal class PlaneTimeline(
    private val minNanos: Long = MIN_NANOS,
    private val smoothingNanos: Long = SMOOTHING_NANOS,
    private val appearNanos: Long = APPEAR_NANOS,
) {
    private val predictor = FractionPredictor()
    private var lastFrame = Long.MIN_VALUE
    private var startedAt = 0L

    /** The file shown; `null` before the first. */
    var shown: SendingItem? = null
        private set

    /** How far its airplane is: folded at [FOLDED], gone at 1. */
    var fraction = 0f
        private set

    /** How far its sheet has faded in, from 0 to 1. */
    var appear = 0f
        private set

    /** The file shown went over the link, or another one goes now: its airplane may fly off. */
    private var done = false

    /**
     * Moves on to [now], in nanoseconds, with the file going over the link at the moment, [item], and the transfer's
     * [bytesPerSecond] if known. `false` while there's nothing to draw: before the first file, and once the last
     * airplane flew off until another file starts.
     */
    fun advance(now: Long, item: SendingItem?, bytesPerSecond: Long?): Boolean {
        val elapsed = if (lastFrame == Long.MIN_VALUE) 0L else (now - lastFrame).coerceAtLeast(0)
        lastFrame = now
        val current = shown
        if (current == null || fraction >= 1f) {
            if (item == null || item.key == current?.key) return false
            start(item, now)
            return true
        }
        if (item != null && item.key == current.key) {
            predictor.report(item.bytesSent, now)
            if (item.finished) done = true
        } else if (item != null) {
            done = true // Another file goes now: this one went.
        }
        val fastest = fraction + elapsed.toFloat() / minNanos
        fraction = if (done) {
            min(fastest, 1f)
        } else {
            val target = predictor.predict(now, bytesPerSecond)
            val eased = fraction + (target - fraction) * (1f - exp(-elapsed.toFloat() / smoothingNanos))
            min(max(eased, fraction), fastest)
        }
        appear = ((now - startedAt).toFloat() / appearNanos).coerceIn(0f, 1f)
        return true
    }

    private fun start(item: SendingItem, now: Long) {
        // The first sheet is there from the start; the next ones come in.
        startedAt = if (shown == null) now - appearNanos else now
        shown = item
        predictor.reset(item, now)
        done = item.finished
        fraction = 0f
        appear = ((now - startedAt).toFloat() / appearNanos).coerceIn(0f, 1f)
    }

    companion object {
        /** Up to here it folds; then it flies. */
        const val FOLDED = 0.35f

        const val MIN_NANOS = 600_000_000L
        private const val SMOOTHING_NANOS = 120_000_000L
        private const val APPEAR_NANOS = 250_000_000L
    }
}
