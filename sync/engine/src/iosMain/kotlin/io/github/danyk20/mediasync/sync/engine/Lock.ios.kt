/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import platform.Foundation.NSCondition

internal actual class Lock actual constructor() {
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

    actual fun signalAll() {
        condition.broadcast()
    }
}
