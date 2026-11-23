/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

/**
 * A mutual-exclusion lock with one condition. NOT reentrant on any platform: a nested [withLock] on the same lock
 * deadlocks on iOS, and the JVM actual fails fast instead.
 */
expect class Lock() {
    /** Runs [block] holding the lock, and returns what it returns. */
    fun <T> withLock(block: () -> T): T

    /** Releases the lock and waits for [signalAll]; call only inside [withLock]. May wake spuriously: loop. */
    fun await()

    /**
     * Like [await], but gives up after [timeoutMillis]. Returns false when the time ran out, true when woken (which
     * may be spurious: the caller loops).
     */
    fun await(timeoutMillis: Long): Boolean

    /** Wakes every waiter; call only inside [withLock]. */
    fun signalAll()
}
