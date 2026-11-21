/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test

class SendRangeTest {
    private val zurich = TimeZone.of("Europe/Zurich")

    @Test
    fun anOpenEndHasNoMoment() {
        SendRange.Everything.startMillis(zurich).shouldBeNull()
        SendRange.Everything.endMillis(zurich).shouldBeNull()
        SendRange.Everything.isEverything shouldBe true
    }

    @Test
    fun theRangeCoversWholeLocalDaysBothIncluded() {
        val day = LocalDate(2026, 3, 29) // the 23-hour day the clocks go forward
        val range = SendRange(day, day)
        range.startMillis(zurich) shouldBe Instant.parse("2026-03-28T23:00:00Z").toEpochMilliseconds()
        range.endMillis(zurich) shouldBe Instant.parse("2026-03-29T22:00:00Z").toEpochMilliseconds()
    }

    @Test
    fun aDayWhoseMidnightDaylightSavingSkipsStartsAtTheJump() {
        val sao = TimeZone.of("America/Sao_Paulo") // 2018-11-04: 00:00 became 01:00
        SendRange(from = LocalDate(2018, 11, 4)).startMillis(sao) shouldBe
            Instant.parse("2018-11-04T03:00:00Z").toEpochMilliseconds()
    }

    @Test
    fun javaZonesGiveTheSameMoments() {
        val range = SendRange(LocalDate(2026, 10, 25), LocalDate(2026, 10, 25))
        range.startMillis(ZoneId.of("Europe/Zurich")) shouldBe range.startMillis(zurich)
        range.endMillis(ZoneId.of("Europe/Zurich")) shouldBe range.endMillis(zurich)
    }
}
