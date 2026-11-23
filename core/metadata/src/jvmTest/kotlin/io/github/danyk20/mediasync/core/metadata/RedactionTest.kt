/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RedactionTest {

    /** Random files with random sorted, non-overlapping edits. */
    private val edited = arbitrary {
        val size = Arb.int(0..5_000).bind()
        val file = Arb.byteArray(Arb.constant(size), Arb.byte()).bind()
        val edits = mutableListOf<Edit>()
        var at = 0L
        while (at < size && edits.size < 20) {
            at += Arb.long(0L..size / 4L + 1).bind()
            if (at > size) break
            val length = Arb.long(0L..minOf(300L, size - at)).bind()
            val replacement = ByteArray(Arb.int(0..300).bind()) { (it * 7).toByte() }
            if (length > 0 || replacement.isNotEmpty()) edits += Edit(at, length, replacement)
            at += length + 1
        }
        file to edits
    }

    @Test
    fun editsStreamExactlyAsTheyApplyInMemory() = runTest {
        checkAll(edited) { (file, edits) ->
            val redaction = Redaction.Edited(edits, file.size.toLong())

            val streamed = redaction.open(file.inputStream()).use { it.readBytes() }

            streamed.toList() shouldBe reference(file, edits).toList()
            streamed.size.toLong() shouldBe redaction.outputSize
        }
    }

    @Test
    fun aResumedTransferSkipsToTheSameBytes() = runTest {
        checkAll(edited, Arb.int(0..6_000)) { (file, edits), skip ->
            val redaction = Redaction.Edited(edits, file.size.toLong())
            val whole = reference(file, edits)
            val from = minOf(skip, whole.size)

            val rest = redaction.open(file.inputStream()).use { stream ->
                stream.skip(from.toLong()) shouldBe from.toLong()
                ByteArrayOutputStream().also { out ->
                    var b = stream.read() // Single bytes too.
                    while (b != -1) {
                        out.write(b)
                        b = stream.read()
                    }
                }.toByteArray()
            }

            rest.toList() shouldBe whole.copyOfRange(from, whole.size).toList()
        }
    }

    @Test
    fun overlappingEditsAreRejected() {
        shouldThrow<IllegalArgumentException> { Redaction.Edited(listOf(Edit(0, 5), Edit(4, 1)), 10) }
        shouldThrow<IllegalArgumentException> { Redaction.Edited(listOf(Edit(8, 5)), 10) }
    }

    @Test
    fun unknownFormatsAreWithheldAndPictureOnlyFormatsSentAsTheyAre() {
        plan("a text file, not a photo".toByteArray()) shouldBe Redaction.Withheld(WithheldReason.UNSUPPORTED)
        plan("GIF89a and pixels".toByteArray()) shouldBe Redaction.None
        plan(ByteArray(0)) shouldBe Redaction.None
    }

    @Test
    fun aDamagedFileIsWithheldNotSentWithItsDetails() {
        val photo = golden("pixel.jpg")

        plan(photo.copyOf(photo.size / 2)) shouldBe Redaction.Withheld(WithheldReason.UNSUPPORTED) // No end.
        plan(photo.copyOf(40)) shouldBe Redaction.Withheld(WithheldReason.UNSUPPORTED) // Cut in its EXIF.
        val video = golden("video.mp4")
        plan(video.copyOf(video.size - 100)) shouldBe Redaction.Withheld(WithheldReason.UNSUPPORTED)
        val webp = golden("photo.webp")
        plan(webp.copyOf(200)) shouldBe Redaction.Withheld(WithheldReason.UNSUPPORTED)
    }

    @Test
    fun countsJudgeByTypeWhatSendingJudgesByContent() {
        isWithheld("image/x-adobe-dng", PhotoDetails.REMOVE_ALL) shouldBe true
        isWithheld("image/x-adobe-dng", PhotoDetails.REMOVE_LOCATION) shouldBe false
        isWithheld("video/webm", PhotoDetails.REMOVE_LOCATION) shouldBe true
        isWithheld("video/webm", PhotoDetails.KEEP_ALL) shouldBe false
        listOf("image/jpeg", "image/heic", "video/mp4", "video/quicktime").forEach {
            isWithheld(it, PhotoDetails.REMOVE_ALL) shouldBe false
        }
    }

    @Test
    fun canonRawVideosOfIsoMediaAreWithheld() {
        val crx = ByteBuffer.allocate(24).putInt(24).put("ftyp".latin1()).put("crx ".latin1()).putInt(1)
            .put("crx ".latin1()).put("isom".latin1()).array()

        plan(crx, PhotoDetails.REMOVE_LOCATION) shouldBe Redaction.Withheld(WithheldReason.RAW)
    }

    @Test
    fun iptcPlacesAfterTheMpfSegmentKeepTheGainMapFindable() {
        // An Ultra HDR photo with an IPTC block after its MPF segment, as a photo editor may write it.
        val photo = golden("ultrahdr.jpg")
        val iptc = segment(golden("pixel.jpg"), APP13)
        val mpf = segment(photo, APP2, "MPF")
        val mpfEnd = indexOf(photo, mpf) + mpf.size
        val withIptc = photo.copyOfRange(0, mpfEnd) + iptc + photo.copyOfRange(mpfEnd, photo.size)
        val mpfTiff = indexOf(withIptc, mpf) + 8
        moveGainMap(withIptc, mpfTiff, iptc.size)
        gainMapStart(withIptc, mpfTiff).let { withIptc.copyOfRange(it, it + 2).toList() } shouldBe START

        val sent = (plan(withIptc, PhotoDetails.REMOVE_LOCATION) as Redaction.Edited)
            .open(withIptc.inputStream()).use { it.readBytes() }

        sent.size shouldBe (withIptc.size - (iptc.size - segment(sent, APP13).size))
        gainMapStart(sent, mpfTiff).let { sent.copyOfRange(it, it + 2).toList() } shouldBe START
        String(sent, Charsets.ISO_8859_1).contains("Zurich") shouldBe false
        String(sent, Charsets.ISO_8859_1).contains("Lake at dawn") shouldBe true // The caption stays.
    }

    @Test
    fun extendedXmpIsCleanedAcrossItsSegments() {
        val property = "<photoshop:City>Zurich</photoshop:City>"
        val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/">""" +
            """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/">$property""" +
            """<photoshop:Credit>Anna</photoshop:Credit></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val bytes = packet.latin1()
        val split = packet.indexOf("Zur") + 2 // The property straddles the two segments.
        val guid = "0123456789ABCDEF0123456789ABCDEF".latin1()
        fun part(from: Int, to: Int): ByteArray {
            val payload = "http://ns.adobe.com/xmp/extension/\u0000".latin1() + guid +
                ByteBuffer.allocate(8).putInt(bytes.size).putInt(from).array() + bytes.copyOfRange(from, to)
            val length = ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array()
            return byteArrayOf(0xFF.toByte(), APP1.toByte()) + length + payload
        }
        val plain = golden("whatsapp.jpg")
        val photo = plain.copyOfRange(0, 2) + part(split, bytes.size) + part(0, split) +
            plain.copyOfRange(2, plain.size)

        val sent = (plan(photo, PhotoDetails.REMOVE_LOCATION) as Redaction.Edited)
            .open(photo.inputStream()).use { it.readBytes() }

        val text = String(sent, Charsets.ISO_8859_1)
        sent.size shouldBe photo.size
        listOf("Zu", "rich", "photoshop:City").filter { it in text } shouldBe emptyList()
        text.contains("<photoshop:Credit>Anna</photoshop:Credit>") shouldBe true
    }

    private fun plan(file: ByteArray, details: PhotoDetails = PhotoDetails.REMOVE_ALL) =
        PhotoDetailsRemover.plan(file.size.toLong(), file.asReader(), details)

    private fun reference(file: ByteArray, edits: List<Edit>): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        for (edit in edits) {
            out.write(file, at, edit.offset.toInt() - at)
            out.write(edit.bytes)
            at = edit.end.toInt()
        }
        out.write(file, at, file.size - at)
        return out.toByteArray()
    }

    /** The first segment with [marker] (and [id]) in a JPEG, header included. */
    private fun segment(jpeg: ByteArray, marker: Int, id: String = ""): ByteArray {
        var at = 2
        while (true) {
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            val payload = String(jpeg, at + 4, minOf(id.length, length - 2), Charsets.ISO_8859_1)
            if ((jpeg[at + 1].toInt() and 0xFF) == marker && payload == id) return jpeg.copyOfRange(at, at + 2 + length)
            at += 2 + length
        }
    }

    private fun indexOf(file: ByteArray, part: ByteArray): Int =
        (0..file.size - part.size).first { i -> part.indices.all { file[i + it] == part[it] } }

    /** The gain map's offset, the second MP entry, in a big-endian MPF segment whose TIFF starts at [mpfTiff]. */
    private fun gainMapStart(file: ByteArray, mpfTiff: Int): Int =
        mpfTiff + ByteBuffer.wrap(file).getInt(gainMapOffsetAt(file, mpfTiff))

    private fun moveGainMap(file: ByteArray, mpfTiff: Int, by: Int) {
        val at = gainMapOffsetAt(file, mpfTiff)
        ByteBuffer.wrap(file).putInt(at, ByteBuffer.wrap(file).getInt(at) + by)
    }

    private fun gainMapOffsetAt(file: ByteArray, mpfTiff: Int): Int {
        val buffer = ByteBuffer.wrap(file)
        val ifd = mpfTiff + buffer.getInt(mpfTiff + 4)
        val entry = (0 until buffer.getShort(ifd).toInt()).map { ifd + 2 + it * 12 }
            .first { buffer.getShort(it).toInt() and 0xFFFF == 0xB002 }
        return mpfTiff + buffer.getInt(entry + 8) + 16 + 8
    }

    private companion object {
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val APP13 = 0xED
        val START = listOf(0xFF.toByte(), 0xD8.toByte())

        fun golden(name: String): ByteArray =
            RedactionTest::class.java.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }
    }
}
