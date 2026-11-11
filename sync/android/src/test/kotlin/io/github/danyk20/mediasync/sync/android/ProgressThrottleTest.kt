/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.SendingItem
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import org.junit.Test

class ProgressThrottleTest {

    private var nanos = 0L
    private val throttle = ProgressThrottle(intervalNanos = 50, now = { nanos })

    private fun progress(done: Long, items: Int = 0, key: String = "a", sent: Long = done, size: Long = 100) =
        TransferProgress(
            itemsDone = items,
            itemsTotal = 3,
            bytesDone = done,
            bytesTotal = 300,
            currentItem = null,
            sending = SendingItem(key, "$key.jpg", size, video = false, bytesSent = sent, startedNanos = 0),
        )

    @Test
    fun chunksShowAtMostOnceAnInterval() {
        throttle.due(progress(10)) shouldBe true
        nanos = 20
        throttle.due(progress(20)) shouldBe false
        nanos = 49
        throttle.due(progress(30)) shouldBe false
        nanos = 50
        throttle.due(progress(40)) shouldBe true
    }

    @Test
    fun turnsAlwaysShow() {
        throttle.due(progress(10)) shouldBe true
        throttle.due(progress(100)) shouldBe true // All of "a" sent.
        throttle.due(progress(100)) shouldBe false
        throttle.due(progress(100, items = 1)) shouldBe true // "a" confirmed.
        throttle.due(progress(100, items = 1, key = "b", sent = 0)) shouldBe true // "b" starts.
        throttle.due(progress(110, items = 1, key = "b", sent = 10)) shouldBe false
        throttle.due(progress(110, items = 1, key = "b", sent = 10).copy(testFile = true)) shouldBe true
    }

    @Test
    fun theLastProgressAlwaysShows() {
        throttle.due(progress(290, items = 3, key = "c", sent = 90)) shouldBe true
        throttle.due(progress(300, items = 3, key = "c", sent = 100)) shouldBe true
        throttle.due(progress(300, items = 3, key = "c", sent = 100)) shouldBe true
    }
}
