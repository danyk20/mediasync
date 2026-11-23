/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class TimestampTest {

    @Test
    fun zurichIsTwoHoursAheadInSummer() {
        formatTimestamp(Instant.parse("2026-10-14T07:30:00.250Z"), TimeZone.of("Europe/Zurich")) shouldBe
            "2026-10-14 09:30:00.250 +0200"
    }

    @Test
    fun utcPrintsAZeroOffsetAndPadsMillis() {
        formatTimestamp(Instant.parse("2026-01-05T03:04:05.007Z"), TimeZone.UTC) shouldBe
            "2026-01-05 03:04:05.007 +0000"
    }

    @Test
    fun aHalfHourOffsetRollsTheDateOver() {
        formatTimestamp(Instant.parse("2026-03-01T23:59:59.999Z"), TimeZone.of("Asia/Kolkata")) shouldBe
            "2026-03-02 05:29:59.999 +0530"
    }

    @Test
    fun aNegativeHalfHourOffsetInDaylightTime() {
        formatTimestamp(Instant.parse("2026-10-14T07:30:00.250Z"), TimeZone.of("America/St_Johns")) shouldBe
            "2026-10-14 05:00:00.250 -0230"
    }

    @Test
    fun millisecondsAreCutNotRounded() {
        formatTimestamp(Instant.fromEpochSeconds(1_792_000_000, 999_999), TimeZone.UTC) shouldEndWith ".000 +0000"
    }
}
