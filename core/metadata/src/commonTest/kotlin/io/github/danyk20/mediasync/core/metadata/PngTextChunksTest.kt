/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/** PNG text chunks: the XMP location and the *Remove all* of an `iTXt` chunk, compressed or not (docs/PLAN.md §6.4). */
class PngTextChunksTest {
    @Test
    fun aCompressedXmpHasItsPlaceBlankedAndStaysValid() {
        val png = png(compressed = true)
        val edited = plan(png, PhotoDetails.REMOVE_LOCATION)
        val out = apply(png, edited.edits)

        val text = chunksOf(out).single { it.type == "iTXt" }.data
        val packet = Zlib.inflate(text.copyOfRange(TEXT_AT, text.size)).latin1String()
        packet shouldNotContain "Zurich"
        packet shouldNotContain "photoshop:City"
        packet shouldContain "<photoshop:Credit>Anna</photoshop:Credit>"
        packet.length shouldBe PACKET.length
        chunksOf(out).single { it.type == "iTXt" }.crc shouldBe crc32("iTXt".latin1() + text)
        untouched(png, out, "IHDR", "IEND")
    }

    @Test
    fun anUncompressedXmpIsBlankedInPlace() {
        val png = png(compressed = false)
        val edited = plan(png, PhotoDetails.REMOVE_LOCATION)
        val out = apply(png, edited.edits)

        out.size shouldBe png.size
        val text = chunksOf(out).single { it.type == "iTXt" }.data.copyOfRange(TEXT_AT, PACKET.length + TEXT_AT)
            .latin1String()
        text shouldNotContain "Zurich"
        text shouldContain "<photoshop:Credit>Anna</photoshop:Credit>"
        untouched(png, out, "IHDR", "IEND")
    }

    @Test
    fun removeAllDropsTheTextChunks() {
        val png = png(compressed = true)
        val edited = plan(png, PhotoDetails.REMOVE_ALL)
        val out = apply(png, edited.edits)

        chunksOf(out).map { it.type } shouldBe listOf("IHDR", "IEND")
        untouched(png, out, "IHDR", "IEND")
    }

    private fun plan(png: ByteArray, details: PhotoDetails): Redaction.Edited =
        PhotoDetailsRemover.plan(png.size.toLong(), png.asReader(), details) as Redaction.Edited

    private fun untouched(before: ByteArray, after: ByteArray, vararg types: String) {
        for (type in types) {
            val kept = chunksOf(after).single { it.type == type }.bytes.toList()
            kept shouldBe chunksOf(before).single { it.type == type }.bytes.toList()
        }
    }

    private class Chunk(val type: String, val bytes: ByteArray, val data: ByteArray, val crc: Long)

    private fun chunksOf(png: ByteArray): List<Chunk> {
        val file = ByteBuffer.wrap(png)
        val chunks = mutableListOf<Chunk>()
        var at = SIGNATURE.size
        while (at < png.size) {
            val length = file.getInt(at)
            val type = png.latin1String(at + 4, 4)
            val data = png.copyOfRange(at + 8, at + 8 + length)
            val crc = file.getInt(at + 8 + length).toLong() and UINT
            chunks += Chunk(type, png.copyOfRange(at, at + 12 + length), data, crc)
            at += 12 + length
        }
        return chunks
    }

    private fun png(compressed: Boolean): ByteArray {
        val text = if (compressed) Zlib.deflate(PACKET.latin1()) else PACKET.latin1()
        val flag: Byte = if (compressed) 1 else 0
        val itxt = XMP_KEYWORD.latin1() + byteArrayOf(0, flag, 0, 0, 0) + text
        return SIGNATURE + chunk("IHDR", ByteArray(13)) + chunk("iTXt", itxt) + chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + data.size + 4).putInt(data.size).put(type.latin1()).put(data)
            .putInt(crc32(type.latin1() + data).toInt()).array()

    private companion object {
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val XMP_KEYWORD = "XML:com.adobe.xmp"

        /** Keyword, NUL, compression flag, method, NUL (no language), NUL (no translated keyword), then the text. */
        const val TEXT_AT = 22
        const val UINT = 0xFFFFFFFFL
        const val PACKET = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\">" +
            "<photoshop:City>Zurich</photoshop:City><photoshop:Credit>Anna</photoshop:Credit>" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
    }
}
