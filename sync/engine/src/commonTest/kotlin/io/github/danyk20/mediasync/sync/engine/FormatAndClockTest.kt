/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class FormatAndClockTest {

    @Test
    fun oneDecimalRoundsHalfUpAsThePercentFormatDoes() {
        oneDecimal(0.0) shouldBe "0.0"
        oneDecimal(0.04) shouldBe "0.0"
        oneDecimal(0.05) shouldBe "0.1"
        oneDecimal(0.94) shouldBe "0.9"
        oneDecimal(0.95) shouldBe "1.0"
        oneDecimal(9.99) shouldBe "10.0"
        oneDecimal(1234.56) shouldBe "1234.6"
        oneDecimal(1e9) shouldBe "1000000000.0"
    }

    @Test
    fun monotonicNanosNeverGoBackAndFollowTheClock() {
        val first = monotonicNanos()
        var last = first
        repeat(1_000) {
            val now = monotonicNanos()
            now shouldBeGreaterThanOrEqual last
            last = now
        }
        val start = TimeSource.Monotonic.markNow()
        var spins = 0
        while (start.elapsedNow() < WAIT) spins++ // Spin: the clock must move on while we wait.
        (spins > 0) shouldBe true
        (monotonicNanos() - first) shouldBeGreaterThan WAIT_NANOS
        (monotonicNanos() >= last) shouldBe true
    }

    private companion object {
        val WAIT = 50.milliseconds
        const val WAIT_NANOS = 40_000_000L
    }
}
