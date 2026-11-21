/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import io.github.danyk20.mediasync.core.designsystem.component.isAfterDaysBefore
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test

/**
 * The "within the last week" cut-off of `pastMoment`. The expected results are what the Android app gave before the
 * move to Compose Multiplatform (`ZonedDateTime.isAfter(now.minusDays(6))`), including across Europe/Zurich's clock
 * change.
 */
class RecentMomentTest {
    private val zurich = TimeZone.of("Europe/Zurich")

    @Test
    fun cutOffIsTheSameWallClockTimeSixDaysBefore() {
        // 4 April 2026, 12:00 CEST: six days earlier is 29 March 12:00, after the clock change, so CEST (10:00 UTC).
        val now = Instant.parse("2026-04-04T12:00:00+02:00")

        isAfterDaysBefore(Instant.parse("2026-03-29T12:01:00+02:00"), now, zurich, 6) shouldBe true
        isAfterDaysBefore(Instant.parse("2026-03-29T12:00:00+02:00"), now, zurich, 6) shouldBe false
    }

    @Test
    fun wallClockTimeInTheSpringGapIsMovedForward() {
        // Zurich's clocks jump from 02:00 to 03:00 on 29 March 2026. Six days before 02:30 on 4 April is 02:30 on
        // 29 March, which does not exist: java.time moves it to 03:30 CEST (01:30 UTC).
        val now = Instant.parse("2026-04-04T02:30:00+02:00")

        isAfterDaysBefore(Instant.parse("2026-03-29T03:00:00+02:00"), now, zurich, 6) shouldBe false
        isAfterDaysBefore(Instant.parse("2026-03-29T03:45:00+02:00"), now, zurich, 6) shouldBe true
    }
}
