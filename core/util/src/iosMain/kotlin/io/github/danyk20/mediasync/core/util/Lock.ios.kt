/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

actual class Lock actual constructor() {
    private val condition = NSCondition()

    actual fun <T> withLock(block: () -> T): T {
        condition.lock()
        try {
            return block()
        } finally {
            condition.unlock()
        }
    }

    actual fun await() {
        condition.wait()
    }

    actual fun await(timeoutMillis: Long): Boolean =
        condition.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(timeoutMillis / MILLIS_PER_SECOND))

    actual fun signalAll() {
        condition.broadcast()
    }
}

private const val MILLIS_PER_SECOND = 1000.0
