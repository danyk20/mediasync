/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * [Lock.await] and [Lock.signalAll] as the sessions' queues use them: one producer, one consumer, no hand-over lost.
 */
class LockAwaitTest {

    @Test
    fun aConsumerReceivesEveryItemTheProducerAdds(): Unit = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            withContext(Dispatchers.Default) {
                val lock = Lock()
                val queue = ArrayDeque<Int>() // guarded by lock

                val producer = async {
                    repeat(ITEMS) { item ->
                        lock.withLock {
                            queue.addLast(item)
                            lock.signalAll()
                        }
                    }
                }
                val consumer = async {
                    List(ITEMS) {
                        lock.withLock {
                            while (queue.isEmpty()) lock.await()
                            queue.removeFirst()
                        }
                    }
                }

                producer.await()
                consumer.await() shouldBe List(ITEMS) { it }
            }
        }
    }

    private companion object {
        const val ITEMS = 10_000
        const val TIMEOUT_MILLIS = 30_000L
    }
}
