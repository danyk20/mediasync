/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.xmp.XmpDirectory
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The golden media set (core/metadata/golden/make_golden.py) under every *Photo details* choice, checked with an
 * independent reader, metadata-extractor, and by searching the bytes themselves (docs/PLAN.md §6.5).
 */
class GoldenMediaTest {

    @ParameterizedTest
    @ValueSource(strings = [JPEG, SAMSUNG, MOTION, ULTRA_HDR, WHATSAPP, PNG, WEBP, HEIC, DNG, MP4, MOV, HDR])
    fun keepAllSendsEveryFileByteForByte(name: String) {
        PhotoDetailsRemover.plan(golden(name).size.toLong(), golden(name).asReader(), PhotoDetails.KEEP_ALL) shouldBe
            Redaction.None
    }

    @ParameterizedTest
    @ValueSource(strings = [JPEG, SAMSUNG, MOTION, ULTRA_HDR, PNG, WEBP, HEIC, DNG, MP4, MOV, HDR])
    fun removeLocationLeavesNoPlaceAnywhere(name: String) {
        val original = golden(name)
        described(original).locationFindings().shouldNotBe(emptyList<String>()) // The set carries a location.

        val sent = redact(original, PhotoDetails.REMOVE_LOCATION)

        described(sent).locationFindings().shouldBeEmpty()
        String(sent, Charsets.ISO_8859_1).let { bytes -> PLACES.filter { it in bytes } }.shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = [JPEG, SAMSUNG, MOTION, ULTRA_HDR, PNG, WEBP, HEIC, DNG])
    fun removeLocationKeepsEverythingElse(name: String) {
        val sent = described(redact(golden(name), PhotoDetails.REMOVE_LOCATION))

        sent shouldContain TAKEN
        sent shouldContain "Lake at dawn"
        if (name != SAMSUNG && name != MOTION) {
            // In XMP, which metadata-extractor doesn't read from every format.
            String(redact(golden(name), PhotoDetails.REMOVE_LOCATION), Charsets.ISO_8859_1) shouldContain "holiday"
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [JPEG, SAMSUNG, MOTION, ULTRA_HDR, PNG, WEBP, HEIC, MP4, MOV, HDR])
    fun removeAllLeavesNothingPersonal(name: String) {
        val sent = redact(golden(name), PhotoDetails.REMOVE_ALL)

        val description = described(sent)
        description.locationFindings().shouldBeEmpty()
        PERSONAL.filter { it in description }.shouldBeEmpty()
        String(sent, Charsets.ISO_8859_1).let { bytes -> (PLACES + PERSONAL).filter { it in bytes } }.shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = [JPEG, SAMSUNG, MOTION, ULTRA_HDR, PNG, WEBP, HEIC])
    fun removeAllKeepsTheCaptureDateAndOrientation(name: String) {
        val sent = described(redact(golden(name), PhotoDetails.REMOVE_ALL))

        sent shouldContain TAKEN
        sent shouldContain "+02:00"
        sent shouldContain "Rotate 90 CW"
    }

    @ParameterizedTest
    @ValueSource(strings = [JPEG, SAMSUNG, MOTION, ULTRA_HDR, WHATSAPP, PNG, DNG])
    fun thePictureStaysTheSame(name: String) {
        val original = golden(name)
        for (details in listOf(PhotoDetails.REMOVE_LOCATION, PhotoDetails.REMOVE_ALL)) {
            if (name == DNG && details == PhotoDetails.REMOVE_ALL) continue // Withheld.
            pixels(redact(original, details)) shouldBe pixels(original)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [MP4, MOV, HDR, HEIC])
    fun videosAndHeicKeepTheirSizeSoNothingMoves(name: String) {
        val original = golden(name)
        for (details in listOf(PhotoDetails.REMOVE_LOCATION, PhotoDetails.REMOVE_ALL)) {
            val sent = redact(original, details)
            sent.size shouldBe original.size
            if (name != HEIC) mediaData(sent) shouldBe mediaData(original)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [JPEG, PNG])
    fun colourProfilesStay(name: String) {
        for (details in listOf(PhotoDetails.REMOVE_LOCATION, PhotoDetails.REMOVE_ALL)) {
            described(redact(golden(name), details)) shouldContain "MediaSync test sRGB"
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [MOTION, ULTRA_HDR, SAMSUNG])
    fun removeLocationKeepsMotionPhotosAndUltraHdrWorking(name: String) {
        val original = golden(name)
        val sent = redact(original, PhotoDetails.REMOVE_LOCATION)

        sent.size shouldBe original.size
        val firstImageEnd = Jpeg(original.asReader(), 0, original.size.toLong()).imageEnd.toInt()
        // What follows the photo (gain map, video) is where it was, with its own location removed.
        String(sent, firstImageEnd, sent.size - firstImageEnd, Charsets.ISO_8859_1).let { tail ->
            PLACES.filter { it in tail }.shouldBeEmpty()
        }
        if (name == MOTION) described(sent) shouldContain "GCamera:MotionPhoto | 1"
        if (name == ULTRA_HDR) {
            described(sent) shouldContain "hdrgm:Version"
            pixels(sent.copyOfRange(firstImageEnd, sent.size)) shouldBe
                pixels(original.copyOfRange(firstImageEnd, original.size))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [MOTION, ULTRA_HDR, SAMSUNG])
    fun removeAllMakesThemOrdinaryPhotos(name: String) {
        val original = golden(name)
        val sent = redact(original, PhotoDetails.REMOVE_ALL)

        sent.size.toLong() shouldBeLessThan Jpeg(original.asReader(), 0, original.size.toLong()).imageEnd
        Jpeg(sent.asReader(), 0, sent.size.toLong()).imageEnd shouldBe sent.size.toLong()
        String(sent, Charsets.ISO_8859_1) shouldNotContainAny listOf("ftyp", "MPF", "hdrgm", "MotionPhoto")
    }

    @ParameterizedTest
    @ValueSource(strings = [WHATSAPP])
    fun aPhotoWithoutDetailsIsSentAsItIs(name: String) {
        for (details in listOf(PhotoDetails.REMOVE_LOCATION, PhotoDetails.REMOVE_ALL)) {
            PhotoDetailsRemover.plan(golden(name).size.toLong(), golden(name).asReader(), details) shouldBe
                Redaction.None
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [DNG])
    fun rawPhotosAreWithheldUnderRemoveAll(name: String) {
        PhotoDetailsRemover.plan(golden(name).size.toLong(), golden(name).asReader(), PhotoDetails.REMOVE_ALL) shouldBe
            Redaction.Withheld(WithheldReason.RAW)
    }

    private infix fun String.shouldNotContainAny(fragments: List<String>) = fragments.filter {
        it in this
    }.shouldBeEmpty()

    internal companion object {
        const val JPEG = "pixel.jpg"
        const val SAMSUNG = "samsung.jpg"
        const val MOTION = "motion.jpg"
        const val ULTRA_HDR = "ultrahdr.jpg"
        const val WHATSAPP = "whatsapp.jpg"
        const val PNG = "screenshot.png"
        const val WEBP = "photo.webp"
        const val HEIC = "photo.heic"
        const val DNG = "raw.dng"
        const val MP4 = "video.mp4"
        const val MOV = "video.mov"
        const val HDR = "hdr.mp4"

        const val TAKEN = "2026:10:14 09:30:00"

        /** Places in the golden set, as text and as coordinates. */
        val PLACES = listOf("Zurich", "Seefeld", "Bellevue", "Switzerland", "+47.37", "47.3769", "008.54")

        /** Camera, device, person and captions in the golden set. */
        val PERSONAL = listOf(
            "Pixel 8", "SM-S911B", "samsung", "iPhone 15", "Anna", "Lake at dawn", "holiday", "A1B2C3", "Main camera",
            "MediaSync tests", "Screenshots", "Shared", "Lavf",
        )

        fun golden(name: String): ByteArray =
            GoldenMediaTest::class.java.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }

        fun redact(original: ByteArray, details: PhotoDetails): ByteArray =
            when (val plan = PhotoDetailsRemover.plan(original.size.toLong(), original.asReader(), details)) {
                Redaction.None -> original

                is Redaction.Edited -> plan.open(original.inputStream()).use { it.readBytes() }.also {
                    it.size.toLong() shouldBe plan.outputSize
                }

                is Redaction.Withheld -> plan.shouldBeInstanceOf<Redaction.Edited>().let { ByteArray(0) }
            }

        /** Every tag metadata-extractor finds, with XMP properties, one per line. */
        fun described(file: ByteArray): String {
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(file), file.size.toLong())
            return metadata.directories.flatMap { directory ->
                directory.tags.map { "${directory.name} | ${it.tagName} | ${it.description}" } +
                    (
                        (directory as? XmpDirectory)?.xmpProperties?.map { (key, value) ->
                            "XMP | $key | $value"
                        }
                        ).orEmpty()
            }.joinToString("\n")
        }

        fun String.locationFindings(): List<String> = lines().filter { line ->
            val name = line.substringAfter(" | ").substringBefore(" | ")
            listOf("GPS", "Latitude", "Longitude", "City", "Country", "Location", "Province", "6709").any {
                name.contains(it, ignoreCase = true)
            } && line.substringAfterLast(" | ").isNotBlank()
        }

        fun pixels(image: ByteArray): List<Int> {
            val decoded = ImageIO.read(ByteArrayInputStream(image)) ?: error("Not decodable")
            return decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width).toList()
        }

        /** The video samples: the payload of the `mdat` box. */
        fun mediaData(file: ByteArray): List<Byte> {
            val at = String(file, Charsets.ISO_8859_1).indexOf("mdat") - 4
            val size = java.nio.ByteBuffer.wrap(file, at, 4).int
            return file.copyOfRange(at + 8, at + size).toList()
        }
    }
}
