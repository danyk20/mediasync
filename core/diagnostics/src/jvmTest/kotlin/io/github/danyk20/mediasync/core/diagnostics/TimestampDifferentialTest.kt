/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** The hand-written [formatTimestamp] must print what the old java.time pattern printed, byte for byte. */
class TimestampDifferentialTest {

    @Test
    fun formatTimestampMatchesTheOldJavaTimePattern() {
        val pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xx")
        val zones = listOf(
            "Europe/Zurich",
            "UTC",
            "Asia/Kolkata",
            "Asia/Kathmandu",
            "America/St_Johns",
            "Pacific/Chatham",
            "America/Los_Angeles",
            "Australia/Lord_Howe",
        )
        val random = Random(42)
        val instants = List(2_000) { random.nextLong(946_684_800_000L, 2_208_988_800_000L) }

        for (id in zones) {
            for (millis in instants) {
                val expected = pattern.format(
                    ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.of(id)),
                )
                formatTimestamp(Instant.fromEpochMilliseconds(millis), TimeZone.of(id)) shouldBe expected
            }
        }
    }
}
