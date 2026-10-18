/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Speed and time left, for the progress shown while syncing. */
class TransferEstimatorTest {

    private var time = 0L
    private val estimator = TransferEstimator(now = { time })

    private fun at(seconds: Double, bytesDone: Long, bytesTotal: Long = 100 * MB): TransferEstimate? {
        time = (seconds * 1_000).toLong()
        return estimator.update(TransferProgress(0, 10, bytesDone, bytesTotal, null))
    }

    @Test
    fun nothingIsEstimatedUntilThereIsEnoughToGoOn() {
        at(0.0, 0) shouldBe null
        at(2.0, 4 * MB) shouldBe null
        at(4.9, 9 * MB) shouldBe null
        at(5.0, 10 * MB) shouldBe TransferEstimate(2 * MB, 45_000) // 90 MB left at 2 MB/s.
    }

    @Test
    fun theSpeedFollowsTheLastTenSecondsNotTheWholeTransfer() {
        at(0.0, 0)
        at(10.0, 10 * MB) // 1 MB/s at first,
        at(15.0, 30 * MB)
        at(20.0, 50 * MB) shouldBe TransferEstimate(4 * MB, 12_500) // then 4 MB/s: the slow start no longer counts.
    }

    @Test
    fun aStalledLinkHasNoSpeedAndNoTimeLeft() {
        at(0.0, 0)
        at(6.0, 12 * MB)
        time = 30_000 // Nothing came since.

        estimator.refresh() shouldBe TransferEstimate(0, null)
    }

    @Test
    fun aNewTransferStartsOver() {
        at(0.0, 0)
        at(6.0, 60 * MB, bytesTotal = 60 * MB) shouldBe TransferEstimate(10 * MB, 0)

        at(7.0, 1 * MB) shouldBe null // Fewer bytes than before: another transfer, warming up again.
        estimator.refresh() shouldBe null
    }

    @Test
    fun theTimeLeftIsSaidAsPeopleSayIt() {
        fun left(seconds: Long) = TransferEstimate(1, seconds * 1_000).minutesLeft()

        left(59) shouldBe 0 // Under a minute.
        left(60) shouldBe 1
        left(61) shouldBe 2 // Rounded up: never "about 1 min" with more to go.
        left(59 * 60) shouldBe 59
        left(61 * 60) shouldBe 65 // From an hour on, in steps of 5 minutes.
        left(125 * 60) shouldBe 125
        TransferEstimate(0, null).minutesLeft() shouldBe null
    }

    private companion object {
        const val MB = 1_000_000L
    }
}
