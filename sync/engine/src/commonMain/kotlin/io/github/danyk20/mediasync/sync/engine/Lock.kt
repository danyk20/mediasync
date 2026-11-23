/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

/**
 * A mutual-exclusion lock with one condition. NOT reentrant on any platform: a nested [withLock] on the same lock
 * deadlocks on iOS, and the JVM actual fails fast instead.
 */
internal expect class Lock() {
    /** Runs [block] holding the lock, and returns what it returns. */
    fun <T> withLock(block: () -> T): T

    /** Releases the lock and waits for [signalAll]; call only inside [withLock]. May wake spuriously: loop. */
    fun await()

    /** Wakes every waiter; call only inside [withLock]. */
    fun signalAll()
}
