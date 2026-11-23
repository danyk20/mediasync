/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.datetime.LocalDateTime

class ExifDateTextTest {
    @Test
    fun anOrdinaryDateIsRead() {
        parseExifDate("2026:10:14 09:30:00") shouldBe LocalDateTime(2026, 10, 14, 9, 30, 0)
    }

    @Test
    fun aDayPastTheMonthsEndMovesToItsLastDay() {
        parseExifDate("2026:02:30 00:00:00") shouldBe LocalDateTime(2026, 2, 28, 0, 0, 0)
        parseExifDate("2024:02:31 00:00:00") shouldBe LocalDateTime(2024, 2, 29, 0, 0, 0)
        parseExifDate("2026:04:31 00:00:00") shouldBe LocalDateTime(2026, 4, 30, 0, 0, 0)
    }

    @Test
    fun midnightAtTheDaysEndIsTheNextDay() {
        parseExifDate("2026:12:31 24:00:00") shouldBe LocalDateTime(2027, 1, 1, 0, 0, 0)
        parseExifDate("2026:12:31 24:00:01") shouldBe null
    }

    @Test
    fun zerosAndOddShapesGiveNone() {
        val texts = listOf(
            "0000:00:00 00:00:00", "0000:01:01 00:00:00", "2026:13:01 00:00:00", "2026:10:14T09:30:00",
            " 2026:10:14 09:30:00", "2026:10:14 09:30", "2026:10:14 25:00:00", "2026:10:14 09:60:00",
            "12026:10:14 09:30:00",
        )
        for (text in texts) parseExifDate(text) shouldBe null
    }
}
