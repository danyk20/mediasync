/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.metadata.GoldenMediaTest.Companion.described
import io.github.danyk20.mediasync.core.metadata.GoldenMediaTest.Companion.golden
import io.github.danyk20.mediasync.core.metadata.GoldenMediaTest.Companion.mediaData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Capture dates for files without one inside (docs/PLAN.md §6.2, point 3). */
class CaptureDateTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "PXL_20250512_103045123.jpg, 2025-05-12T10:30:45.123Z", // Pixel names are in UTC.
        "IMG_20250512_103045.jpg, 2025-05-12T10:30:45+02:00[Europe/Zurich]",
        "VID_20250512_103045.mp4, 2025-05-12T10:30:45+02:00[Europe/Zurich]",
        "20250512_103045.jpg, 2025-05-12T10:30:45+02:00[Europe/Zurich]",
        "Screenshot_20250512-103045.png, 2025-05-12T10:30:45+02:00[Europe/Zurich]",
        "Screenshot_2025-05-12-10-30-45-120_com.android.chrome.jpg, 2025-05-12T10:30:45.120+02:00[Europe/Zurich]",
        "IMG-20250512-WA0003.jpg, 2025-05-12T12:00+02:00[Europe/Zurich]", // A day only: noon.
    )
    fun datesInFileNamesAreFound(name: String, expected: String) {
        CaptureDateGuess.fromName(name, modifiedAtMillis = null, zone = zurich) shouldBe millis(expected)
    }

    @Test
    fun aDayOnlyNameTakesTheTimeTheFileWasSavedThatDay() {
        val savedThatEvening = millis("2025-05-12T19:15:02+02:00[Europe/Zurich]")
        val savedNextMorning = millis("2025-05-13T08:00+02:00[Europe/Zurich]")

        CaptureDateGuess.fromName("IMG-20250512-WA0003.jpg", savedThatEvening, zurich) shouldBe savedThatEvening
        CaptureDateGuess.fromName("IMG-20250512-WA0003.jpg", savedNextMorning, zurich) shouldBe
            millis("2025-05-12T12:00+02:00[Europe/Zurich]")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "photo.jpg", "IMG_1234.jpg", "IMG_12345678.jpg", "DSC_20251340_101010.jpg", "IMG_19800512_101010.jpg",
            "1234567890123.jpg",
        ],
    )
    fun namesWithoutAPlausibleDateGiveNone(name: String) {
        CaptureDateGuess.fromName(name, modifiedAtMillis = null, zone = zurich).shouldBeNull()
    }

    @Test
    fun aNameDateAfterTheFileLastChangedIsntTrusted() {
        val modified = millis("2025-05-10T10:00Z")

        CaptureDateGuess.fromName("IMG_20250512_103045.jpg", modified, zurich).shouldBeNull()
    }

    @Test
    fun theGalleryComesFirstThenTheNameThenTheModificationTime() {
        val gallery = millis("2024-01-02T03:04:05Z")
        val modified = millis("2025-06-01T08:00Z")

        CaptureDateGuess.resolve(gallery, "IMG-20250512-WA0003.jpg", modified, zurich) shouldBe
            CaptureDate(gallery, DateSource.GALLERY)
        CaptureDateGuess.resolve(null, "IMG-20250512-WA0003.jpg", modified, zurich) shouldBe
            CaptureDate(millis("2025-05-12T12:00+02:00[Europe/Zurich]"), DateSource.FILE_NAME)
        CaptureDateGuess.resolve(0, "download.jpg", modified, zurich) shouldBe
            CaptureDate(modified, DateSource.MODIFIED)
        CaptureDateGuess.resolve(null, "download.jpg", null, zurich).shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(strings = [GoldenMediaTest.MP4, GoldenMediaTest.MOV, GoldenMediaTest.HDR])
    fun aVideosCaptureTimeIsReadFromItsMovieHeader(name: String) {
        val video = golden(name)

        VideoDates.capturedAtMillis(video.size.toLong(), video.asReader()) shouldBe millis("2026-10-14T07:30:00Z")
        VideoDates.dateEdits(video.size.toLong(), video.asReader(), millis("2020-01-01T00:00Z")).shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = [GoldenMediaTest.MP4, GoldenMediaTest.MOV, GoldenMediaTest.HDR])
    fun aVideoWithoutCaptureTimeGetsOneWithoutAnyOtherChange(name: String) {
        val original = golden(name)
        val undated = withoutTimes(original)
        VideoDates.capturedAtMillis(undated.size.toLong(), undated.asReader()).shouldBeNull()

        val edits = VideoDates.dateEdits(undated.size.toLong(), undated.asReader(), millis("2026-10-14T07:30:00Z"))
        val repaired = undated.copyOf().also { file -> edits.forEach { it.bytes.copyInto(file, it.offset.toInt()) } }

        edits.forEach { it.bytes.size.toLong() shouldBe it.length } // Same size: every offset stays valid.
        VideoDates.capturedAtMillis(repaired.size.toLong(), repaired.asReader()) shouldBe
            millis("2026-10-14T07:30:00Z")
        mediaData(repaired) shouldBe mediaData(original)
        // An independent reader sees the same creation time as in the original.
        creationTimes(described(repaired)) shouldBe creationTimes(described(original))
    }

    @Test
    fun onlyTheMissingTimesAreWritten() {
        val video = golden(GoldenMediaTest.MP4)
        val headers = Isobmff(video.asReader(), 0, video.size.toLong()).timeHeaders()
        val undated = withoutTimes(video, only = headers.first { it.type == "mvhd" })

        val edits = VideoDates.dateEdits(undated.size.toLong(), undated.asReader(), millis("2026-10-14T07:30:00Z"))

        edits shouldHaveSize 2 // The movie's creation and modification times; the tracks keep theirs.
    }

    @ParameterizedTest
    @ValueSource(strings = [GoldenMediaTest.JPEG, GoldenMediaTest.HEIC, GoldenMediaTest.PNG])
    fun photosAreNotVideos(name: String) {
        val photo = golden(name)

        VideoDates.capturedAtMillis(photo.size.toLong(), photo.asReader()).shouldBeNull()
        VideoDates.dateEdits(photo.size.toLong(), photo.asReader(), 0).shouldBeEmpty()
    }

    @Test
    fun aDamagedVideoIsLeftAlone() {
        val damaged = withoutTimes(golden(GoldenMediaTest.MP4)).let { it.copyOf(it.size / 2) }

        VideoDates.capturedAtMillis(damaged.size.toLong(), damaged.asReader()).shouldBeNull()
        VideoDates.dateEdits(damaged.size.toLong(), damaged.asReader(), 0).shouldBeEmpty()
        VideoDates.capturedAtMillis(4, byteArrayOf(0, 0, 0, 8).asReader()).shouldBeNull()
    }

    @Test
    fun timesAfter2040FitOnlyInLongHeaders() {
        val undated = withoutTimes(golden(GoldenMediaTest.MP4))

        VideoDates.dateEdits(undated.size.toLong(), undated.asReader(), millis("2041-01-01T00:00Z")).shouldBeEmpty()
    }

    /** [video] with the creation and modification times of [only] (default: every header) set to zero. */
    private fun withoutTimes(video: ByteArray, only: Isobmff.Box? = null): ByteArray {
        val headers = Isobmff(video.asReader(), 0, video.size.toLong()).timeHeaders()
        return video.copyOf().also { file ->
            headers.filter { only == null || it.at == only.at }.forEach { header ->
                val width = if (file[header.payloadAt.toInt()].toInt() == 0) 8 else 16
                ByteArray(width).copyInto(file, header.payloadAt.toInt() + 4)
            }
        }
    }

    private fun creationTimes(description: String): List<String> =
        description.lines().filter { it.contains("Creation Time") }.also { it.joinToString() shouldContain "2026" }

    private fun millis(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()
}
