/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8

/** The queue the sessions share on their own threads: every item goes to exactly one connection. */
class WorkQueueTest {

    private fun item(index: Int) = SourceItem(
        key = "k$index",
        descriptor = MediaDescriptor(
            fingerprint = "f$index".encodeUtf8(),
            size = ITEM_BYTES,
            mimeType = "image/jpeg",
            displayName = "$index.jpg",
        ),
    )

    @Test
    fun concurrentBatchesTakeEveryItemExactlyOnce(): Unit = runBlocking {
        val queue = WorkQueue(List(ITEMS) { item(it) })

        val taken = withContext(Dispatchers.Default) {
            List(WORKERS) { worker ->
                async {
                    val mine = mutableListOf<SourceItem>()
                    while (true) {
                        val batch = queue.take(maxItems = 1 + worker % MAX_BATCH, maxBytes = Long.MAX_VALUE)
                        if (batch.isEmpty()) break
                        mine += batch
                    }
                    mine
                }
            }.awaitAll().flatten()
        }

        taken.size shouldBe ITEMS
        taken.map { it.key }.toSet() shouldBe List(ITEMS) { "k$it" }.toSet()
        queue.isEmpty() shouldBe true
    }

    @Test
    fun aBatchEndsOnceItReachesTheByteLimit() {
        val queue = WorkQueue(List(ITEMS) { item(it) })

        // Each item is ITEM_BYTES: two reach the limit of 2 * ITEM_BYTES.
        queue.take(maxItems = MAX_BATCH, maxBytes = 2 * ITEM_BYTES).size shouldBe 2
    }

    @Test
    fun theTestFileGoesToExactlyOneRacingCaller(): Unit = runBlocking {
        val file = TestFile(size = ITEM_BYTES, seed = 1)
        val queue = WorkQueue(emptyList(), file)

        val winners = withContext(Dispatchers.Default) {
            List(CALLERS) { async { queue.takeTestFile() } }.awaitAll()
        }

        winners.filterNotNull() shouldBe listOf(file)
        queue.takeTestFile() shouldBe null
    }

    @Test
    fun sendingTestFileBecomesFalseWhenTheTakerIsDone(): Unit = runBlocking {
        val queue = WorkQueue(emptyList(), TestFile(size = ITEM_BYTES, seed = 1))
        queue.sendingTestFile shouldBe true

        withContext(Dispatchers.Default) {
            async {
                queue.takeTestFile()
                queue.testFileDone()
            }.await()
        }

        queue.sendingTestFile shouldBe false
    }

    private companion object {
        const val ITEMS = 1_000
        const val WORKERS = 4
        const val MAX_BATCH = 5
        const val CALLERS = 8
        const val ITEM_BYTES = 10L
    }
}
