/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Speed and time left, for the progress shown while syncing. */
class TransferEstimatorTest {

    private var time = 0L
    private val estimator = TransferEstimator(now = { time })

    private fun at(
        seconds: Double,
        bytesDone: Long,
        bytesTotal: Long = 100 * MB,
        bytesSkipped: Long = 0,
    ): TransferEstimate? {
        time = (seconds * 1_000).toLong()
        return estimator.update(TransferProgress(0, 10, bytesDone, bytesTotal, null, bytesSkipped))
    }

    /** One second of a [transfer]: what the estimator said, and how long the transfer really still took. */
    private data class Second(val at: Int, val estimate: TransferEstimate?, val trulyLeftMillis: Long) {
        val leftMillis: Long get() = estimate!!.remainingMillis!!

        /** How far the time left is off, as a share of the truth. */
        val error: Double get() = abs(leftMillis - trulyLeftMillis) / trulyLeftMillis.toDouble()
    }

    /** Sends [total] bytes at [speed] (bytes a second, in each second since the start), with progress every 100 ms. */
    private fun transfer(total: Long, speed: (second: Int) -> Long): List<Second> {
        val doneAt = mutableListOf(0L) // Each tenth of a second.
        while (doneAt.last() < total) doneAt += minOf(total, doneAt.last() + speed((doneAt.size - 1) / 10) / 10)
        val end = doneAt.size - 1
        return doneAt.mapIndexedNotNull { tenth, done ->
            val estimate = at(tenth / 10.0, done, bytesTotal = total)
            if (tenth % 10 == 0) Second(tenth / 10, estimate, (end - tenth) * 100L) else null
        }
    }

    @Test
    fun nothingIsEstimatedUntilThereIsEnoughToGoOn() {
        at(0.0, 0) shouldBe null
        at(2.0, 4 * MB) shouldBe null
        at(4.9, 9_800_000) shouldBe null
        at(5.0, 10 * MB) shouldBe TransferEstimate(2 * MB, 45_000) // 90 MB left at 2 MB/s.
    }

    @Test
    fun aSteadyLinkGivesTheExactTimeLeft() {
        val seconds = transfer(300 * MB) { 2 * MB }

        seconds.drop(5).forEach { it.estimate shouldBe TransferEstimate(2 * MB, it.trulyLeftMillis) }
    }

    @Test
    fun theSpeedIsTheOneNowButTheTimeLeftDoesNotSwingWithIt() {
        // 15 seconds at 1 MB/s, then 15 at 3 MB/s, and so on: the speed jumps threefold all the time.
        val seconds = transfer(600 * MB) { second -> if (second / 15 % 2 == 0) MB else 3 * MB }
        val estimates = seconds.mapNotNull { it.estimate }

        // The speed shows the swings, as they happen.
        estimates.map { it.bytesPerSecond } shouldContainAll listOf(MB, 3 * MB)
        // Once the start is behind (it was slow, so the first estimates are long), the time left only counts down,
        // never up, and stays near the truth; bytes left ÷ the speed now would swing threefold.
        val settled = seconds.filter { it.at >= 90 && it.trulyLeftMillis > 30_000 }
        settled.zipWithNext { earlier, later ->
            val change = later.leftMillis - earlier.leftMillis
            change shouldBeLessThan 0
            change shouldBeGreaterThan -2_500
        }
        settled.forEach { it.error shouldBeLessThan 0.2 }
    }

    @Test
    fun aLinkThatSlowsDownForGoodRaisesTheTimeLeftGradually() {
        // 4 MB/s for a minute, then 1 MB/s to the end: the time left grows from 1 minute to 4.
        val seconds = transfer(480 * MB) { second -> if (second < 60) 4 * MB else MB }

        // It never jumps: it rises less than a second each second.
        seconds.filter { it.at >= 60 }.zipWithNext { earlier, later ->
            later.leftMillis - earlier.leftMillis shouldBeLessThan 1_000
        }
        seconds.single { it.at == 70 }.estimate!!.bytesPerSecond shouldBe MB // The speed shows it within 10 seconds.
        // Within 15 % of the truth two and a half minutes later.
        seconds.filter { it.at >= 60 + 150 && it.trulyLeftMillis > 0 }.forEach { it.error shouldBeLessThan 0.15 }
    }

    @Test
    fun aStalledLinkHasNoSpeedAndNoTimeLeftUntilItMovesAgain() {
        at(0.0, 0)
        at(6.0, 12 * MB)
        time = 30_000 // Nothing came since.

        estimator.refresh() shouldBe TransferEstimate(0, null)

        val again = at(31.0, 14 * MB)!! // Moving again: no warming up.
        again.bytesPerSecond shouldBeGreaterThan 0
        again.remainingMillis shouldNotBe null
    }

    @Test
    fun bytesThatDidNotCrossTheLinkAreDoneButNotSpeed() {
        at(0.0, 0)
        at(5.0, 10 * MB) shouldBe TransferEstimate(2 * MB, 45_000)

        // A file resumes: the 40 MB the other phone kept are done at once, but nothing moved faster. They come off
        // the time left at once: 48 MB at 2 MB/s.
        at(6.0, 52 * MB, bytesSkipped = 40 * MB) shouldBe TransferEstimate(2 * MB, 24_000)
    }

    @Test
    fun aNewTransferStartsOver() {
        at(0.0, 0)
        at(6.0, 60 * MB, bytesTotal = 60 * MB) shouldBe TransferEstimate(10 * MB, 0)

        estimator.reset()

        estimator.refresh() shouldBe null
        at(7.0, 1 * MB) shouldBe null // Warming up again.
        at(12.0, 11 * MB) shouldBe TransferEstimate(2 * MB, 44_500)
    }

    @Test
    fun progressThatComesLateChangesNothing() {
        at(0.0, 0)
        at(6.0, 12 * MB) shouldBe TransferEstimate(2 * MB, 44_000)

        // Another connection's thread reported a moment before, but came after: same transfer, no warming up again.
        at(6.0, 11 * MB) shouldBe TransferEstimate(2 * MB, 44_000)
        at(7.0, 14 * MB) shouldBe TransferEstimate(2 * MB, 43_000)
        estimator.history() shouldBe listOf(SpeedSample(6_000, 2 * MB), SpeedSample(7_000, 2 * MB))
    }

    @Test
    fun theSpeedIsRecordedOnceASecondForTheChartAndANewTransferStartsAnew() {
        at(0.0, 0)
        at(5.0, 10 * MB)
        at(5.4, 11 * MB) // Within the same second: not recorded again.
        at(6.0, 12 * MB)
        time = 20_000 // Stalled: the refresh records the drop.
        estimator.refresh()

        estimator.history() shouldBe listOf(
            SpeedSample(5_000, 2 * MB),
            SpeedSample(6_000, 2 * MB),
            SpeedSample(20_000, 0),
        )

        estimator.reset() // Another transfer.
        estimator.history() shouldBe emptyList()
    }

    @Test
    fun theTimeLeftIsSaidAsPeopleSayIt() {
        fun left(seconds: Long) = TransferEstimate(1, seconds * 1_000).secondsLeft()

        left(0) shouldBe 10 // The last seconds never read 0.
        left(31) shouldBe 40 // Under a minute, in steps of 10 seconds, rounded up.
        left(50) shouldBe 50
        left(51) shouldBe 60 // "About 1 min".
        left(61) shouldBe 120 // Rounded up: never "about 1 min" with more to go.
        left(59 * 60) shouldBe 59 * 60
        left(61 * 60) shouldBe 65 * 60 // From an hour on, in steps of 5 minutes.
        left(125 * 60) shouldBe 125 * 60
        TransferEstimate(1, 30_001).secondsLeft() shouldBe 40 // Part of a second counts as one.
        TransferEstimate(0, null).secondsLeft() shouldBe null
    }

    private companion object {
        const val MB = 1_000_000L
    }
}
