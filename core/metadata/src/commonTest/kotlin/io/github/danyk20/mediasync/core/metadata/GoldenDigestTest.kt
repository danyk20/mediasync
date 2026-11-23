/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.datetime.TimeZone
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.buffer
import okio.use

/**
 * The golden media set, read through the same parsers and redactor on every platform, compared line by line with
 * [GoldenDigests] (generated from the JVM before the iPhone port). The formats are the ones the generator wrote.
 */
class GoldenDigestTest {

    @Test
    fun everyGoldenFileComesOutByteForByteAsOnTheJvmBase() {
        val lines = NAMES.flatMap { name ->
            val original = goldenBytes(name)
            listOf(PhotoDetails.REMOVE_LOCATION, PhotoDetails.REMOVE_ALL).map { details ->
                "redaction|$name|$details|${outcome(original, details)}"
            }
        }

        lines.joinToString("\n") shouldBe GoldenDigests.REDACTION
    }

    @Test
    fun capturedDatesAreTheSameOnEveryPlatform() {
        val lines = NAMES.flatMap { name ->
            val original = goldenBytes(name)
            val dates = ZONES.map { zone ->
                val at = EmbeddedDates.capturedAt(original.size.toLong(), original.asReader(), TimeZone.of(zone))
                "dates|$name|$zone|" +
                    (at?.let { "${it.epochMillis},${it.utcOffsetMinutes},${it.offsetKnown}" } ?: "null")
            }
            dates + "video|$name|${VideoDates.capturedAtMillis(original.size.toLong(), original.asReader())}"
        }

        lines.joinToString("\n") shouldBe GoldenDigests.DATES
    }

    @Test
    fun captureDatesFromNamesAreTheSameOnEveryPlatform() {
        val zone = TimeZone.of("Europe/Zurich") // Includes the DST gap and overlap names.
        val lines = FILE_NAMES.map { "guess|$it|${CaptureDateGuess.fromName(it, null, zone)}" }

        lines.joinToString("\n") shouldBe GoldenDigests.GUESSES
    }

    private fun outcome(original: ByteArray, details: PhotoDetails): String =
        when (val plan = PhotoDetailsRemover.plan(original.size.toLong(), original.asReader(), details)) {
            Redaction.None -> "NONE"

            is Redaction.Withheld -> "WITHHELD:${plan.reason}"

            is Redaction.Edited -> plan.open(Buffer().write(original)).buffer().use { it.readByteArray() }
                .let { "EDITED:${it.size}:${it.toByteString().sha256().hex()}" }
        }

    private companion object {
        val NAMES = listOf(
            "pixel.jpg", "samsung.jpg", "motion.jpg", "ultrahdr.jpg", "whatsapp.jpg", "screenshot.png", "photo.webp",
            "photo.heic", "raw.dng", "video.mp4", "video.mov", "hdr.mp4",
        )
        val ZONES = listOf("UTC", "Asia/Tokyo", "America/New_York")
        val FILE_NAMES = listOf(
            "PXL_20250512_103045123.jpg", "IMG_20250512_103045.jpg", "20250512_103045.jpg",
            "Screenshot_20250512-103045.png", "Screenshot_2025-05-12-10-30-45-120_com.android.chrome.jpg",
            "IMG-20250512-WA0003.jpg", "photo.jpg",
            "IMG_20250330_023000.jpg", "IMG_20251026_023000.jpg", "IMG_20251340_101010.jpg",
        )
    }
}
