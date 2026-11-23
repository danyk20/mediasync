/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.toKotlinLocalDateTime
import org.junit.jupiter.api.Test

/** [parseExifDate] and [parseUtcOffsetSeconds] against the java.time code they replace: java.time is the reference. */
class ExifDateDifferentialTest {
    private val pattern = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    private fun javaDate(text: String) = try {
        LocalDateTime.parse(text, pattern).toKotlinLocalDateTime()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun javaOffset(text: String) = try {
        ZoneOffset.of(text).totalSeconds
    } catch (_: DateTimeException) {
        null
    }

    @Test
    fun exifDatesAreReadLikeJavaTime() = runTest {
        checkAll(
            Arb.int(0..9999),
            Arb.int(0..13),
            Arb.int(0..32),
            Arb.int(0..25),
            Arb.int(0..61),
            Arb.int(0..61),
        ) { year, month, day, hour, minute, second ->
            val text = "%04d:%02d:%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
            parseExifDate(text) shouldBe javaDate(text)
        }
        val known = listOf(
            "2026:12:31 24:00:00",
            "2026:12:31 24:00:01",
            "2026:02:30 12:00:00",
            "2024:02:31 00:00:00",
            "2026:04:31 00:00:00",
            "0000:01:01 00:00:00",
            "2026:10:14 09:30:00",
            "2026:10:14 24:30:00",
        )
        for (text in known) parseExifDate(text) shouldBe javaDate(text)
        val mutated = listOf(
            "2026-10-14 09:30:00", "2026:10:14T09:30:00", "2026:10:14 09:30:00 ", " 2026:10:14 09:30:00",
            "2026:10:14 09:30:0", "2026:10:14  09:30:00", "2026/10/14 09:30:00", "2026:10:14 09:30:00\u0000",
            "202610140930:00", "2026:10:14 09:30:00Z",
        )
        for (text in mutated) parseExifDate(text) shouldBe javaDate(text)
    }

    @Test
    fun offsetsAreReadLikeZoneOffsetOf() = runTest {
        val characters = "+-:0123456789Zz".toList()
        checkAll(Arb.list(Arb.element(characters), 0..10)) { picked ->
            val text = picked.joinToString("")
            parseUtcOffsetSeconds(text) shouldBe javaOffset(text)
        }
        checkAll(Arb.element("+", "-"), Arb.int(0..99), Arb.int(0..99), Arb.int(0..99), Arb.int(0..5)) {
                sign,
                hours,
                minutes,
                seconds,
                form,
            ->
            val text = when (form) {
                0 -> "%s%02d".format(sign, hours)
                1 -> "%s%02d:%02d".format(sign, hours, minutes)
                2 -> "%s%02d%02d".format(sign, hours, minutes)
                3 -> "%s%02d:%02d:%02d".format(sign, hours, minutes, seconds)
                4 -> "%s%02d%02d%02d".format(sign, hours, minutes, seconds)
                else -> "%s%d".format(sign, hours)
            }
            parseUtcOffsetSeconds(text) shouldBe javaOffset(text)
        }
    }
}
