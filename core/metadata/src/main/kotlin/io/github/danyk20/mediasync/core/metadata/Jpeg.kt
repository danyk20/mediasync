/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.nio.ByteBuffer

/**
 * A JPEG from [start] to [end] in the file [reader] reads: its metadata segments (before the image data), where
 * the image ends, and what follows it (an Ultra HDR gain map, a Motion Photo's video, a Samsung trailer).
 */
internal class Jpeg(private val reader: ByteReader, private val start: Long, private val end: Long) : Editor {

    /** A marker segment: `FF`, [marker], a 2-byte length, the payload. */
    class Segment(val marker: Int, val at: Long, val totalLength: Int) {
        val payloadAt: Long get() = at + HEADER
        val payloadSize: Int get() = totalLength - HEADER
        val end: Long get() = at + totalLength
    }

    private val bytes = Blocks(reader, end)
    val segments: List<Segment>

    /** Just after the image's end marker. */
    val imageEnd: Long

    init {
        if (bytes.byte(start) != MARK || bytes.byte(start + 1) != SOI) malformed("Not a JPEG at $start")
        val found = mutableListOf<Segment>()
        var at = start + 2
        var header = true
        var imageEnd = -1L
        while (imageEnd < 0) {
            if (at + 1 >= end || bytes.byte(at) != MARK) malformed("JPEG marker expected at $at")
            var markerAt = at
            while (bytes.byte(markerAt + 1) == MARK) markerAt++ // Fill bytes.
            val marker = bytes.byte(markerAt + 1)
            when {
                marker == EOI -> imageEnd = markerAt + 2

                marker in RST0..RST7 || marker == TEM -> at = markerAt + 2

                else -> {
                    val length = bytes.u16(markerAt + 2)
                    if (length < 2) malformed("JPEG segment of $length bytes")
                    val segment = Segment(marker, markerAt, length + 2)
                    if (header && marker != SOS) found += segment
                    at = segment.end
                    if (marker == SOS) {
                        header = false
                        at = bytes.nextMarker(at)
                    }
                }
            }
        }
        segments = found
        this.imageEnd = imageEnd
    }

    /** *Remove location*: GPS, XMP and IPTC places, here and in what follows the image. */
    override fun locationEdits(): List<Edit> {
        val mpf = segments.firstOrNull { it.marker == APP2 && it.hasId(MPF_ID) }
        val iptc = segments.filter { it.marker == APP13 }.mapNotNull(::iptcEdit)
        // The gain map's place is relative to the MPF segment: a smaller segment after it moves the gain map.
        val shift = iptc.filter { mpf != null && it.offset > mpf.at }.sumOf { it.bytes.size - it.length }
        return segments.flatMap(::exifAndXmpLocationEdits) + iptc +
            extendedXmpEdits(segments.filter { it.isApp1(XMP_EXTENSION_ID) }) +
            mpf?.takeIf { shift != 0L }?.let { mpfShift(it, shift) }.orEmpty() +
            trailingLocationEdits()
    }

    private fun exifAndXmpLocationEdits(segment: Segment): List<Edit> = when {
        segment.isApp1(EXIF_ID) -> exif(segment).locationEdits()

        segment.isApp1(XMP_ID) -> {
            val packetAt = segment.payloadAt + XMP_ID.size
            Xmp.locationEdits(reader.read(packetAt, segment.payloadSize - XMP_ID.size)).map { it.shiftedBy(packetAt) }
        }

        else -> emptyList()
    }

    /**
     * *Remove all*: keeps JFIF, the colour profile and Adobe's colour information, replaces EXIF by what
     * [Tiff.minimal] keeps, removes every other metadata segment, and cuts what follows the image.
     */
    override fun allEdits(): List<Edit> {
        val edits = mutableListOf<Edit>()
        for (segment in segments) {
            if (segment.isApp1(EXIF_ID)) {
                val minimal = exif(segment).minimal()
                edits += Edit(
                    segment.at,
                    segment.totalLength.toLong(),
                    minimal?.let { segmentBytes(APP1, EXIF_ID + it) } ?: ByteArray(0),
                )
                continue
            }
            val keep = when (segment.marker) {
                APP0 -> segment.hasId(JFIF_ID)
                APP2 -> segment.hasId(ICC_ID)
                APP14 -> segment.hasId(ADOBE_ID)
                in APP1..APP15, COM -> false
                else -> true
            }
            if (!keep) edits += Edit.delete(segment.at, segment.totalLength.toLong())
        }
        if (imageEnd < end) edits += Edit.delete(imageEnd, end - imageEnd)
        return edits
    }

    /** The Photoshop resources segment without IPTC places, if it has any. */
    private fun iptcEdit(segment: Segment): Edit? =
        PhotoshopResources.withoutLocation(reader.read(segment.payloadAt, segment.payloadSize))
            ?.let { kept -> Edit(segment.at, segment.totalLength.toLong(), segmentBytes(APP13, kept)) }

    private fun exif(segment: Segment) =
        Tiff(reader, segment.payloadAt + EXIF_ID.size, (segment.payloadSize - EXIF_ID.size).toLong())

    /** Extended XMP: one packet split over several segments, which a location property may straddle. */
    private fun extendedXmpEdits(segments: List<Segment>): List<Edit> = segments
        .groupBy { reader.read(it.payloadAt + XMP_EXTENSION_ID.size, GUID).toList() }
        .flatMap { (_, parts) ->
            val chunks = parts.map { segment ->
                val headerAt = segment.payloadAt + XMP_EXTENSION_ID.size + GUID
                val header = ByteBuffer.wrap(reader.read(headerAt, 2 * Int.SIZE_BYTES))
                Chunk(header.getInt(Int.SIZE_BYTES).toLong(), headerAt + 2 * Int.SIZE_BYTES, segment.end)
            }.sortedBy { it.packetOffset }
            val packet = chunks.fold(ByteArray(0)) { all, chunk -> all + reader.read(chunk.at, chunk.size) }
            Xmp.locationEdits(packet).flatMap { blank -> chunks.flatMap { it.part(blank) } }
        }

    /** A part of an extended XMP packet: bytes from [packetOffset] of the packet, at [at] in the file. */
    private class Chunk(val packetOffset: Long, val at: Long, end: Long) {
        val size = (end - at).toInt()

        /** The part of [edit] (in packet offsets) that falls in this chunk, in file offsets. */
        fun part(edit: Edit): List<Edit> {
            val from = maxOf(edit.offset, packetOffset)
            val to = minOf(edit.end, packetOffset + size)
            if (from >= to) return emptyList()
            return listOf(
                Edit.overwrite(
                    at + from - packetOffset,
                    edit.bytes.copyOfRange(
                        (from - edit.offset).toInt(),
                        (to - edit.offset).toInt(),
                    ),
                ),
            )
        }
    }

    /** Moves the images an MPF segment points to by [delta] bytes. */
    private fun mpfShift(segment: Segment, delta: Long): List<Edit> {
        val tiffAt = segment.payloadAt + MPF_ID.size
        val mpf = Tiff(reader, tiffAt, (segment.payloadSize - MPF_ID.size).toLong())
        val entries = mpf.ifd0().find(MP_ENTRY) ?: return emptyList()
        val table = ByteBuffer.wrap(mpf.bytes(entries)).order(mpf.order)
        return (0 until entries.byteCount.toInt() / MP_ENTRY_SIZE).mapNotNull { i ->
            val offset = table.getInt(i * MP_ENTRY_SIZE + MP_OFFSET).toLong() and UINT
            if (offset == 0L) return@mapNotNull null // The primary image.
            val moved = ByteBuffer.allocate(Int.SIZE_BYTES).order(mpf.order).putInt((offset + delta).toInt()).array()
            Edit.overwrite(tiffAt + entries.valueAt + i * MP_ENTRY_SIZE + MP_OFFSET, moved)
        }
    }

    /** After the image: another JPEG (a gain map) with its own details, or a Motion Photo's video. */
    private fun trailingLocationEdits(): List<Edit> {
        if (imageEnd >= end) return emptyList()
        if (end - imageEnd > 2 && bytes.byte(imageEnd) == MARK && bytes.byte(imageEnd + 1) == SOI) {
            return Jpeg(reader, imageEnd, end).locationEdits()
        }
        val video = bytes.find(FTYP, imageEnd + Int.SIZE_BYTES)
            ?.let { it - Int.SIZE_BYTES }
            ?.takeIf { bytes.u32(it) in MIN_FTYP..MAX_FTYP }
            ?: return emptyList()
        return Isobmff(reader, video, end, embedded = true).videoEdits(remove = Isobmff.Remove.LOCATION)
    }

    private fun Segment.isApp1(id: ByteArray) = marker == APP1 && hasId(id)

    private fun Segment.hasId(id: ByteArray) =
        payloadSize >= id.size && reader.read(payloadAt, id.size).contentEquals(id)

    private fun segmentBytes(marker: Int, payload: ByteArray): ByteArray {
        if (payload.size + 2 > MAX_SEGMENT) malformed("Segment of ${payload.size} bytes")
        return ByteBuffer.allocate(HEADER + payload.size)
            .put(MARK.toByte()).put(marker.toByte()).putShort((payload.size + 2).toShort()).put(payload).array()
    }

    private companion object {
        const val HEADER = 4
        const val MARK = 0xFF
        const val SOI = 0xD8
        const val EOI = 0xD9
        const val SOS = 0xDA
        const val TEM = 0x01
        const val RST0 = 0xD0
        const val RST7 = 0xD7
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val APP13 = 0xED
        const val APP14 = 0xEE
        const val APP15 = 0xEF
        const val COM = 0xFE
        const val MAX_SEGMENT = 0xFFFF
        const val GUID = 32
        const val MP_ENTRY = 0xB002
        const val MP_ENTRY_SIZE = 16
        const val MP_OFFSET = 8
        const val UINT = 0xFFFFFFFFL
        const val MIN_FTYP = 8L
        const val MAX_FTYP = 256L

        val EXIF_ID = "Exif\u0000\u0000".latin1()
        val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".latin1()
        val XMP_EXTENSION_ID = "http://ns.adobe.com/xmp/extension/\u0000".latin1()
        val MPF_ID = "MPF\u0000".latin1()
        val ICC_ID = "ICC_PROFILE\u0000".latin1()
        val JFIF_ID = "JFIF\u0000".latin1()
        val ADOBE_ID = "Adobe".latin1()
        val FTYP = "ftyp".latin1()
    }
}

internal fun String.latin1(): ByteArray = toByteArray(Charsets.ISO_8859_1)

/** Reads a file sequentially in blocks, for scanning image data quickly. */
internal class Blocks(private val reader: ByteReader, private val end: Long) {
    private var blockAt = 0L
    private var block = ByteArray(0)

    fun byte(at: Long): Int {
        if (at < blockAt || at >= blockAt + block.size) load(at)
        return block[(at - blockAt).toInt()].toInt() and BYTE
    }

    fun u16(at: Long): Int = (byte(at) shl BYTE_BITS) or byte(at + 1)

    fun u32(at: Long): Long = (0 until Int.SIZE_BYTES).fold(0L) { value, i ->
        (value shl BYTE_BITS) or
            byte(at + i).toLong()
    }

    /** In entropy-coded image data from [from]: the next marker that isn't a stuffed byte or a restart marker. */
    fun nextMarker(from: Long): Long {
        var at = from
        while (true) {
            if (at + 1 >= end) malformed("JPEG image data without an end")
            if (at < blockAt || at + 1 >= blockAt + block.size) load(at)
            val data = block
            var i = (at - blockAt).toInt()
            val last = data.size - 1
            while (i < last && data[i] != FF) i++
            at = blockAt + i
            if (i >= last) continue // The 0xFF may be the block's last byte: reload from it.
            val next = data[i + 1].toInt() and BYTE
            when (next) {
                0x00, in RST_FIRST..RST_LAST -> at += 2
                BYTE -> at += 1
                else -> return at
            }
        }
    }

    /** The position of the first [pattern] at or after [from], or `null`. */
    fun find(pattern: ByteArray, from: Long): Long? {
        var at = from
        while (at + pattern.size <= end) {
            if (at < blockAt || at + pattern.size > blockAt + block.size) load(at)
            val data = block
            val start = (at - blockAt).toInt()
            for (i in start..data.size - pattern.size) {
                if (data[i] == pattern[0] && pattern.indices.all { data[i + it] == pattern[it] }) return blockAt + i
            }
            at = blockAt + data.size - pattern.size + 1
        }
        return null
    }

    private fun load(at: Long) {
        if (at >= end) malformed("Read past the end at $at")
        blockAt = at
        block = reader.read(at, minOf(BLOCK.toLong(), end - at).toInt())
    }

    private companion object {
        const val BLOCK = 64 * 1024
        const val BYTE = 0xFF
        const val BYTE_BITS = 8
        const val FF = 0xFF.toByte()
        const val RST_FIRST = 0xD0
        const val RST_LAST = 0xD7
    }
}
