/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinTimeZone
import org.junit.jupiter.api.Test

/** The java.time overloads give the answers of the kotlinx-datetime functions they forward to. */
class JavaTimeAdaptersTest {
    private val names = listOf(
        "IMG_20250330_023000.jpg",
        "IMG_20251026_023000.jpg",
        "PXL_20250512_103045123.jpg",
        "Screenshot_20250512-103045.png",
        "IMG-20250512-WA0003.jpg",
        "photo.jpg",
    )
    private val modified = 1_747_000_000_000L

    @Test
    fun resolveWithAZoneIdIsResolveWithTheKotlinZone() {
        val zone = ZoneId.of("Europe/Zurich")
        for (name in names) {
            CaptureDateGuess.resolve(null, name, modified, zone) shouldBe
                CaptureDateGuess.resolve(null, name, modified, TimeZone.of("Europe/Zurich"))
            CaptureDateGuess.resolve(1_700_000_000_000L, name, null, zone) shouldBe
                CaptureDateGuess.resolve(1_700_000_000_000L, name, null, TimeZone.of("Europe/Zurich"))
        }
    }

    @Test
    fun fromNameWithAZoneIdIsTheSame() {
        val zone = ZoneId.of("Europe/Zurich")
        for (name in names) {
            CaptureDateGuess.fromName(name, modified, zone) shouldBe
                CaptureDateGuess.fromName(name, modified, TimeZone.of("Europe/Zurich"))
        }
        CaptureDateGuess.fromName("IMG_20250330_023000.jpg", null, zone) shouldBe
            CaptureDateGuess.fromName("IMG_20250330_023000.jpg", null, zone.toKotlinTimeZone())
    }

    @Test
    fun capturedAtWithAZoneIdAndAZoneOffsetIsTheSame() {
        val zones = listOf(ZoneId.of("Asia/Tokyo") to TimeZone.of("Asia/Tokyo"), ZoneOffset.UTC to TimeZone.UTC)
        for (name in listOf("pixel.jpg", "video.mp4")) {
            val bytes = GoldenMediaTest.golden(name)
            for ((zone, kotlinZone) in zones) {
                EmbeddedDates.capturedAt(bytes.size.toLong(), bytes.asReader(), zone) shouldBe
                    EmbeddedDates.capturedAt(bytes.size.toLong(), bytes.asReader(), kotlinZone)
            }
        }
    }
}
