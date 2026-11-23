/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * A PNG: chunks of length, type, data and checksum. Nothing points into a PNG by offset, so chunks may change size
 * or go; changed ones get a new checksum.
 */
internal class Png(private val reader: ByteReader, private val fileSize: Long) : Editor {

    private class Chunk(val type: String, val at: Long, val length: Int) {
        val dataAt: Long get() = at + HEADER
        val end: Long get() = dataAt + length + CRC_SIZE
    }

    private val chunks: List<Chunk> = buildList {
        var at = SIGNATURE_SIZE.toLong()
        while (at < fileSize) {
            if (at + HEADER + CRC_SIZE > fileSize) malformed("PNG chunk header at $at")
            val header = ByteBuffer.wrap(reader.read(at, HEADER))
            val length = header.getInt(0)
            val chunk = Chunk(String(header.array(), 4, 4, Charsets.ISO_8859_1), at, length)
            if (length < 0 || chunk.end > fileSize) malformed("PNG chunk of $length bytes")
            add(chunk)
            at = chunk.end
            if (chunk.type == "IEND") break
        }
    }

    /** *Remove location*: GPS in `eXIf`, places in the XMP; legacy text copies of EXIF and XMP are dropped. */
    override fun locationEdits(): List<Edit> = chunks.flatMap { chunk ->
        when {
            chunk.type == "eXIf" -> {
                val data = data(chunk)
                val edited = apply(data, Tiff(data.asReader(), 0, data.size.toLong()).locationEdits())
                if (edited === data) emptyList() else listOf(replace(chunk, edited))
            }

            chunk.type == "iTXt" && keyword(chunk) == XMP_KEYWORD -> xmpLocation(chunk)

            chunk.isText && keyword(
                chunk,
            ).startsWith(RAW_PROFILE) -> listOf(Edit.delete(chunk.at, chunk.end - chunk.at))

            else -> emptyList()
        }
    }

    /** *Remove all*: `eXIf` keeps only what [Tiff.minimal] keeps; text chunks go. Colour chunks stay. */
    override fun allEdits(): List<Edit> = chunks.flatMap { chunk ->
        when {
            chunk.type == "eXIf" -> {
                val data = data(chunk)
                val minimal = Tiff(data.asReader(), 0, data.size.toLong()).minimal()
                listOf(if (minimal == null) Edit.delete(chunk.at, chunk.end - chunk.at) else replace(chunk, minimal))
            }

            chunk.isText -> listOf(Edit.delete(chunk.at, chunk.end - chunk.at))

            else -> emptyList()
        }
    }

    /** XMP in an `iTXt` chunk: keyword, compression flag and method, language, translated keyword, text. */
    private fun xmpLocation(chunk: Chunk): List<Edit> {
        val data = data(chunk)
        var at = data.zeroAfter(0) + 1
        val compressed = data.getOrNull(at)?.toInt() == 1
        at += 2
        at = data.zeroAfter(at) + 1
        at = data.zeroAfter(at) + 1
        val text = data.copyOfRange(at, data.size)
        val packet = if (compressed) InflaterInputStream(text.inputStream()).use { it.readBytes() } else text
        val blanked = apply(packet, Xmp.locationEdits(packet))
        if (blanked === packet) return emptyList()
        val newText = if (compressed) {
            ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(blanked) } }.toByteArray()
        } else {
            blanked
        }
        return listOf(replace(chunk, data.copyOfRange(0, at) + newText))
    }

    private val Chunk.isText get() = type == "tEXt" || type == "zTXt" || type == "iTXt"

    private fun keyword(chunk: Chunk): String {
        val head = reader.read(chunk.dataAt, minOf(chunk.length, MAX_KEYWORD))
        val end = head.indexOf(0).takeIf { it >= 0 } ?: head.size
        return String(head, 0, end, Charsets.ISO_8859_1)
    }

    private fun data(chunk: Chunk) = reader.read(chunk.dataAt, chunk.length)

    /** The whole [chunk] replaced by one of the same type holding [data]. */
    private fun replace(chunk: Chunk, data: ByteArray): Edit {
        val typeAndData = chunk.type.latin1() + data
        val crc = CRC32().apply { update(typeAndData) }.value
        val bytes = ByteBuffer.allocate(HEADER + data.size + CRC_SIZE)
            .putInt(data.size).put(typeAndData).putInt(crc.toInt()).array()
        return Edit(chunk.at, chunk.end - chunk.at, bytes)
    }

    private fun ByteArray.zeroAfter(from: Int): Int = (from until size).firstOrNull { this[it] == 0.toByte() }
        ?: malformed("PNG text chunk without a terminator")

    private companion object {
        const val SIGNATURE_SIZE = 8
        const val HEADER = 8
        const val CRC_SIZE = 4
        const val MAX_KEYWORD = 80
        const val XMP_KEYWORD = "XML:com.adobe.xmp"

        /** ImageMagick's hex-encoded copies of EXIF, XMP or IPTC ("Raw profile type exif"). */
        const val RAW_PROFILE = "Raw profile type"
    }
}

/**
 * A WebP: a RIFF file of chunks. EXIF and XMP have chunks of their own, announced by flags in `VP8X`. Nothing
 * points into it by offset; the RIFF header holds the total size.
 */
internal class WebP(private val reader: ByteReader, private val fileSize: Long) : Editor {

    private class Chunk(val type: String, val at: Long, val length: Long) {
        val dataAt: Long get() = at + HEADER
        val end: Long get() = dataAt + length + length % 2
    }

    private val chunks: List<Chunk> = buildList {
        var at = RIFF_HEADER.toLong()
        while (at + HEADER <= fileSize) {
            val header = ByteBuffer.wrap(reader.read(at, HEADER)).order(ByteOrder.LITTLE_ENDIAN)
            val length = header.getInt(4).toLong() and UINT
            val chunk = Chunk(String(header.array(), 0, 4, Charsets.ISO_8859_1), at, length)
            if (chunk.dataAt + length > fileSize) malformed("WebP chunk of $length bytes")
            add(chunk)
            at = minOf(chunk.end, fileSize)
        }
    }

    /** *Remove location*: GPS in EXIF and places in XMP, in place. */
    override fun locationEdits(): List<Edit> = chunks.flatMap { chunk ->
        when (chunk.type) {
            "EXIF" -> exif(chunk).locationEdits()

            "XMP " -> Xmp.locationEdits(reader.read(chunk.dataAt, chunk.length.toInt())).map {
                it.shiftedBy(chunk.dataAt)
            }

            else -> emptyList()
        }
    }

    /** *Remove all*: EXIF keeps only what [Tiff.minimal] keeps, XMP goes, and `VP8X` says so. */
    override fun allEdits(): List<Edit> {
        val edits = mutableListOf<Edit>()
        var flagsOff = 0
        for (chunk in chunks) {
            when (chunk.type) {
                "EXIF" -> {
                    val minimal = exif(chunk).minimal()
                    if (minimal == null) flagsOff = flagsOff or EXIF_FLAG
                    edits +=
                        Edit(chunk.at, chunk.end - chunk.at, minimal?.let { chunkBytes("EXIF", it) } ?: ByteArray(0))
                }

                "XMP " -> {
                    flagsOff = flagsOff or XMP_FLAG
                    edits += Edit.delete(chunk.at, chunk.end - chunk.at)
                }
            }
        }
        if (edits.isEmpty()) return edits
        chunks.firstOrNull { it.type == "VP8X" }?.takeIf { flagsOff != 0 }?.let { vp8x ->
            val flags = reader.read(vp8x.dataAt, 1)[0].toInt()
            edits += Edit.overwrite(vp8x.dataAt, byteArrayOf((flags and flagsOff.inv()).toByte()))
        }
        val newSize = fileSize + edits.sumOf { it.bytes.size - it.length }
        edits +=
            Edit.overwrite(
                4,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((newSize - 8).toInt()).array(),
            )
        return edits.sortedBy { it.offset }
    }

    /** EXIF data: a TIFF, after an `Exif\0\0` header in files from some writers. */
    private fun exif(chunk: Chunk): Tiff {
        val prefixed =
            chunk.length >= EXIF_HEADER.size && reader.read(chunk.dataAt, EXIF_HEADER.size).contentEquals(EXIF_HEADER)
        val skip = if (prefixed) EXIF_HEADER.size else 0
        return Tiff(reader, chunk.dataAt + skip, chunk.length - skip)
    }

    private fun chunkBytes(type: String, data: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER + data.size + data.size % 2).order(ByteOrder.LITTLE_ENDIAN)
            .put(type.latin1()).putInt(data.size).put(data).array()

    private companion object {
        const val RIFF_HEADER = 12
        const val HEADER = 8
        const val UINT = 0xFFFFFFFFL
        const val EXIF_FLAG = 0x08
        const val XMP_FLAG = 0x04
        val EXIF_HEADER = "Exif\u0000\u0000".latin1()
    }
}

/** [data] with [edits] applied (in memory), or [data] itself when there are none. */
internal fun apply(data: ByteArray, edits: List<Edit>): ByteArray {
    if (edits.isEmpty()) return data
    return Redaction.Edited(mergeZeros(edits), data.size.toLong()).open(data.inputStream()).use { it.readBytes() }
}
