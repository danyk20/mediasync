/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@EngineInternalApi
actual class Lock actual constructor() {
    private val mutex = ReentrantLock()
    private val changed = mutex.newCondition()

    actual fun <T> withLock(block: () -> T): T {
        // iOS's lock would deadlock here: fail the JVM tests instead.
        check(!mutex.isHeldByCurrentThread) { "Lock is not reentrant" }
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    actual fun await() {
        changed.await()
    }

    actual fun await(timeoutMillis: Long): Boolean = changed.await(timeoutMillis, TimeUnit.MILLISECONDS)

    actual fun signalAll() {
        changed.signalAll()
    }
}
