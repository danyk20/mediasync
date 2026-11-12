/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.TransferProgress

/**
 * Which progress the home screen shows (docs/PLAN.md §7, *Per-file progress*): a sync reports every chunk and every
 * confirmation, far more often than a screen can show. One is shown at most every [intervalNanos], apart from those
 * that must never be missed: an item done, a file starting or all of it sent, the test file starting or ending, and
 * everything done. So the last progress of a sync always shows.
 */
internal class ProgressThrottle(
    private val intervalNanos: Long = INTERVAL_NANOS,
    private val now: () -> Long = System::nanoTime,
) {
    private var last: TransferProgress? = null
    private var lastAt = 0L

    /** Whether to show [progress]; if so, it's the last one shown. */
    @Synchronized
    fun due(progress: TransferProgress): Boolean {
        val time = now()
        val previous = last
        val due = previous == null || time - lastAt >= intervalNanos || turns(previous, progress)
        if (due) {
            last = progress
            lastAt = time
        }
        return due
    }

    private fun turns(previous: TransferProgress, progress: TransferProgress): Boolean {
        val before = previous.sending
        val now = progress.sending
        return progress.itemsDone != previous.itemsDone ||
            progress.testFile != previous.testFile ||
            now?.key != before?.key ||
            (now?.finished == true && before?.finished != true) ||
            progress.bytesDone >= progress.bytesTotal
    }

    private companion object {
        /** About 20 a second: smooth to the eye, and the sending animation fills in between (PLAN §3.3). */
        const val INTERVAL_NANOS = 50_000_000L
    }
}
