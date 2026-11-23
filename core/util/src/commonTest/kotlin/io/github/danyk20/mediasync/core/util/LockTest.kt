/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class LockTest {

    @Test
    fun withLockReturnsWhatTheBlockReturns() {
        Lock().withLock { 42 } shouldBe 42
    }

    @Test
    fun withLockRethrowsAndReleasesTheLock() {
        val lock = Lock()

        shouldThrow<IllegalStateException> { lock.withLock { error("boom") } }

        lock.withLock { "free again" } shouldBe "free again"
    }

    @Test
    fun contendingThreadsNeverLoseAnUpdate() = runTest {
        val lock = Lock()
        var count = 0

        withContext(Dispatchers.Default) {
            List(THREADS) {
                async { repeat(INCREMENTS) { lock.withLock { count++ } } }
            }.awaitAll()
        }

        count shouldBe THREADS * INCREMENTS
    }

    @Test
    fun awaitWakesUpWhenTheConditionIsSignalled() = runTest {
        val lock = Lock()
        var ready = false
        var seen = false

        withContext(Dispatchers.Default) {
            val waiter = async {
                lock.withLock {
                    while (!ready) lock.await()
                    seen = true
                }
            }
            lock.withLock {
                ready = true
                lock.signalAll()
            }
            waiter.await()
        }

        seen shouldBe true
    }

    private companion object {
        const val THREADS = 8
        const val INCREMENTS = 10_000
    }
}
