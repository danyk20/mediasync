/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class CaptureOffsetTest {

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    @Test
    fun theOffsetIsWhereTheLocalTimeMeetsTheMoment() {
        captureOffset("2024:06:01 10:00:00", millis("2024-06-01T08:00:00Z")) shouldBe ZoneOffset.ofHours(2)
        captureOffset("2024:01:15 07:30:00", millis("2024-01-15T12:30:00Z")) shouldBe ZoneOffset.ofHours(-5)
        captureOffset("2024:01:15 12:30:00", millis("2024-01-15T12:30:00Z")) shouldBe ZoneOffset.UTC
    }

    @Test
    fun quarterHourZonesAreKept() {
        // Nepal is UTC+05:45, parts of Australia +09:30.
        captureOffset("2024:03:01 18:45:00", millis("2024-03-01T13:00:00Z")) shouldBe ZoneOffset.ofHoursMinutes(5, 45)
        captureOffset("2024:03:01 21:30:00", millis("2024-03-01T12:00:00Z")) shouldBe ZoneOffset.ofHoursMinutes(9, 30)
    }

    @Test
    fun subSecondsAndSmallClockDifferencesRoundAway() {
        captureOffset("2024:06:01 10:00:00", millis("2024-06-01T08:00:00.731Z")) shouldBe ZoneOffset.ofHours(2)
        captureOffset("2024:06:01 10:00:00", millis("2024-06-01T07:58:10Z")) shouldBe ZoneOffset.ofHours(2)
    }

    @Test
    fun impossibleOrUnreadableDatesGiveNoOffset() {
        // A day apart can't be the same moment in any time zone.
        captureOffset("2024:06:02 10:00:00", millis("2024-06-01T08:00:00Z")).shouldBeNull()
        captureOffset("2024:06:01", millis("2024-06-01T08:00:00Z")).shouldBeNull()
        captureOffset("    :  :     :  :  ", millis("2024-06-01T08:00:00Z")).shouldBeNull()
    }
}
