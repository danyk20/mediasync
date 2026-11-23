/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.metadata.GoldenMediaTest.Companion.golden
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The capture time a computer's photo or video carries inside (docs/PLAN.md §6.2). */
class EmbeddedDatesTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val taken = Instant.parse("2026-10-14T07:30:00Z").toEpochMilli() // 09:30 at +02:00

    private fun datedBy(bytes: ByteArray, zone: ZoneId = tokyo) =
        EmbeddedDates.capturedAt(bytes.size.toLong(), bytes.asReader(), zone)

    @ParameterizedTest
    @ValueSource(strings = ["pixel.jpg", "samsung.jpg", "motion.jpg", "ultrahdr.jpg", "photo.heic", "raw.dng"])
    fun aPhotoHasTheCameraDateAndItsOffset(name: String) {
        datedBy(golden(name)) shouldBe CapturedAt(taken, utcOffsetMinutes = 120, offsetKnown = true)
    }

    @ParameterizedTest
    @ValueSource(strings = ["video.mp4", "video.mov", "hdr.mp4"])
    fun aVideoHasItsCreationTimeAndTheReadersZone(name: String) {
        datedBy(golden(name)) shouldBe CapturedAt(taken, utcOffsetMinutes = 540, offsetKnown = false)
    }

    @ParameterizedTest
    @ValueSource(strings = ["whatsapp.jpg", "screenshot.png", "photo.webp"])
    fun aFileWithoutAReadableDateHasNone(name: String) {
        datedBy(golden(name)).shouldBeNull()
    }

    @Test
    fun aDateWithoutAnOffsetIsReadInTheGivenZone() {
        val bytes = withoutOffset(golden("pixel.jpg"))

        datedBy(bytes, tokyo) shouldBe
            CapturedAt(
                Instant.parse("2026-10-14T00:30:00Z").toEpochMilli(),
                utcOffsetMinutes = 540,
                offsetKnown = false,
            )
        datedBy(bytes, ZoneOffset.UTC) shouldBe CapturedAt(
            Instant.parse("2026-10-14T09:30:00Z").toEpochMilli(),
            utcOffsetMinutes = 0,
            offsetKnown = false,
        )
    }

    @Test
    fun aClocklessCameraGivesNoDate() {
        val bytes = golden("pixel.jpg")
        val text = String(bytes, Charsets.ISO_8859_1)
        // The original and the digitized date: with either left, the date is read from it.
        val zeros = bytes.copyOf().also { copy ->
            Regex("2026:10:14 09:30:00").findAll(text).forEach {
                "0000:00:00 00:00:00".toByteArray().copyInto(copy, it.range.first)
            }
        }

        datedBy(zeros).shouldBeNull()
    }

    @Test
    fun damagedOrUnknownFilesGiveNoDate() {
        val photo = golden("pixel.jpg")
        datedBy(photo.copyOf(100)).shouldBeNull() // Cut inside the EXIF.
        datedBy(ByteArray(64) { 7 }).shouldBeNull()
        datedBy(ByteArray(4)).shouldBeNull()
        datedBy(golden("photo.heic").copyOf(200)).shouldBeNull()
    }

    /** The golden JPEG with its offset tag's value turned into spaces: no offset, as older cameras. */
    private fun withoutOffset(bytes: ByteArray): ByteArray {
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("+02:00")
        return bytes.copyOf().also { "      ".toByteArray().copyInto(it, at) }
    }
}
