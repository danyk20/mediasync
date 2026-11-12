/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import io.github.danyk20.mediasync.sync.engine.SendingItem
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test

/** The airplane's timing (docs/PLAN.md §10.2): as fast as the file goes, and never faster. */
class PlaneTimelineTest {

    private fun file(key: String, sent: Long, size: Long = 10 * MIB) =
        SendingItem(key, "$key.jpg", size, video = false, bytesSent = sent, startedNanos = 0)

    @Test
    fun thePredictionGoesOnAtTheReportedSpeedButNoMoreThanAReportAhead() {
        val predictor = FractionPredictor()
        predictor.reset(file("a", 0), now = 0)
        predictor.predict(now = 0, bytesPerSecond = null) shouldBe 0f

        predictor.report(MIB, now = 100 * MS) // 10 MiB/s.
        predictor.predict(now = 150 * MS, bytesPerSecond = null) shouldBe (0.15f plusOrMinus 0.001f)
        // Stalled: it waits a chunk ahead of the last report.
        predictor.predict(now = 5_000 * MS, bytesPerSecond = null) shouldBe (0.2f plusOrMinus 0.001f)
    }

    @Test
    fun thePredictionNeverGoesBack() {
        val predictor = FractionPredictor()
        predictor.reset(file("a", 0), now = 0)
        predictor.report(MIB, now = 100 * MS)
        val ahead = predictor.predict(now = 200 * MS, bytesPerSecond = null)

        predictor.report(MIB + 1, now = 300 * MS) // Slower than predicted.

        predictor.predict(now = 300 * MS, bytesPerSecond = null) shouldBe ahead
    }

    @Test
    fun beforeTheFirstReportTheTransfersSpeedGuides() {
        val predictor = FractionPredictor()
        predictor.reset(file("a", 0), now = 0)

        predictor.predict(now = 50 * MS, bytesPerSecond = 10L * MIB) shouldBe (0.05f plusOrMinus 0.001f)
    }

    @Test
    fun aNewFileStartsOverAndAnEmptyOneIsDone() {
        val predictor = FractionPredictor()
        predictor.reset(file("a", 9 * MIB), now = 0)
        predictor.predict(now = 0, bytesPerSecond = null) shouldBe 0.9f

        predictor.reset(file("b", 0), now = 10)
        predictor.predict(now = 10, bytesPerSecond = null) shouldBe 0f
        predictor.reset(file("empty", 0, size = 0), now = 20)
        predictor.predict(now = 20, bytesPerSecond = null) shouldBe 1f
    }

    @Test
    fun aBigFileFliesAsFastAsItGoes() {
        val timeline = PlaneTimeline()
        timeline.advance(0, file("video", 0, size = 100 * MIB), null) shouldBe true
        // 10 MiB/s for 5 seconds: halfway.
        var now = 0L
        while (now < 5_000 * MS) {
            now += FRAME
            timeline.advance(now, file("video", now / MS * MIB / 100, size = 100 * MIB), null)
        }

        timeline.fraction shouldBe (0.5f plusOrMinus 0.02f)
        timeline.shown?.key shouldBe "video"
    }

    @Test
    fun aSmallFileTakesTheShortestFlightAndTheNextOneShownIsTheOneGoingThen() {
        val timeline = PlaneTimeline()
        timeline.advance(0, file("a", 0), null)
        timeline.advance(FRAME, file("a", 10 * MIB), null) // Done at once.
        timeline.fraction shouldBeLessThanOrEqual FRAME.toFloat() / PlaneTimeline.MIN_NANOS + 0.001f

        var now = FRAME
        val shown = mutableListOf<String>()
        for (key in listOf("b", "c", "d", "e", "f", "g", "h", "i", "j", "k")) {
            repeat(6) {
                now += FRAME
                timeline.advance(now, file(key, 10 * MIB), null)
                timeline.shown?.key?.let { if (it !in shown) shown += it }
            }
        }

        // Each lasts at least 0.6 s: of ten files in 1 s, only one more shows, the one going when "a" flew off.
        shown shouldBe listOf("a", "h")
    }

    @Test
    fun afterTheLastFileTheCardIsEmptyAndTheNextSheetComesIn() {
        val timeline = PlaneTimeline()
        timeline.advance(0, file("a", 10 * MIB), null)
        timeline.appear shouldBe 1f // The first one is there at once.
        var now = 0L
        while (now < PlaneTimeline.MIN_NANOS) {
            now += FRAME
            timeline.advance(now, file("a", 10 * MIB), null)
        }
        timeline.advance(now + FRAME, file("a", 10 * MIB), null) shouldBe false

        timeline.advance(now + 2 * FRAME, file("b", 0), null) shouldBe true
        timeline.appear shouldBe 0f
        timeline.advance(now + 3 * FRAME, file("b", 0), null)
        timeline.appear shouldBeGreaterThan 0f
        timeline.appear shouldBeLessThan 1f
    }

    @Test
    fun nothingToShowBeforeTheFirstFile() {
        PlaneTimeline().advance(0, null, null) shouldBe false
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val MS = 1_000_000L
        const val FRAME = 16 * MS
    }
}
