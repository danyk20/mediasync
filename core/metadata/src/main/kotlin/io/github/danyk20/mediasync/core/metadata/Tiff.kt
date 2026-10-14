/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The file isn't what its header says, or is damaged: its details can't be removed with certainty. */
internal class MalformedMediaException(message: String) : RuntimeException(message)

internal fun malformed(message: String): Nothing = throw MalformedMediaException(message)

/**
 * A TIFF structure, as EXIF is stored in photos and RAW files are built: directories (IFDs) of tagged values.
 * Offsets inside it are relative to its header at [base] in the file [reader] reads, [size] bytes long.
 */
internal class Tiff(private val reader: ByteReader, private val base: Long, private val size: Long) {

    val order: ByteOrder
    private val firstIfd: Long

    init {
        if (size < HEADER_SIZE) malformed("TIFF of $size bytes")
        val header = reader.read(base, HEADER_SIZE)
        order = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> malformed("No TIFF byte order")
        }
        val buffer = ByteBuffer.wrap(header).order(order)
        if (buffer.getShort(2).toInt() != MAGIC) malformed("Not a TIFF")
        firstIfd = buffer.getInt(FIRST_IFD_AT).toLong() and UINT
    }

    /** One tagged value; its bytes are inline in the entry when they fit in 4 bytes. */
    class Entry(val tag: Int, val type: Int, val count: Long, val valueAt: Long, val byteCount: Long) {
        val inline: Boolean get() = byteCount <= INLINE_BYTES
    }

    /** A directory at [offset]: its [entries] and the offset of the next one (0 for none). */
    class Ifd(val offset: Long, val entries: List<Entry>, val next: Long) {
        val byteCount: Int get() = 2 + entries.size * ENTRY_SIZE + 4

        fun find(tag: Int): Entry? = entries.firstOrNull { it.tag == tag }
    }

    fun ifd0(): Ifd = ifd(firstIfd)

    fun ifd(offset: Long): Ifd {
        if (offset < HEADER_SIZE || offset + 2 > size) malformed("IFD at $offset")
        val count = buffer(offset, 2).getShort(0).toInt() and USHORT
        if (offset + 2 + count.toLong() * ENTRY_SIZE + 4 > size) malformed("IFD at $offset with $count entries")
        val table = buffer(offset + 2, count * ENTRY_SIZE + 4)
        val entries = List(count) { i ->
            val at = i * ENTRY_SIZE
            val tag = table.getShort(at).toInt() and USHORT
            val type = table.getShort(at + 2).toInt() and USHORT
            val valueCount = table.getInt(at + 4).toLong() and UINT
            val byteCount = valueCount * typeSize(type)
            val valueAt = if (byteCount <= INLINE_BYTES) {
                offset + 2 + at + VALUE_FIELD
            } else {
                table.getInt(at + VALUE_FIELD).toLong() and UINT
            }
            if (valueAt + byteCount > size) malformed("Tag 0x${tag.toString(HEX)} runs past the TIFF")
            Entry(tag, type, valueCount, valueAt, byteCount)
        }
        return Ifd(offset, entries, table.getInt(count * ENTRY_SIZE).toLong() and UINT)
    }

    /** The IFD a pointer tag (such as the GPS or EXIF one) in [ifd] points to, if any. */
    fun child(ifd: Ifd, tag: Int): Ifd? = ifd.find(tag)?.let { entry ->
        if (entry.byteCount != INLINE_BYTES.toLong()) malformed("Pointer tag 0x${tag.toString(HEX)}")
        ifd(buffer(entry.valueAt, INLINE_BYTES).getInt(0).toLong() and UINT)
    }

    fun bytes(entry: Entry): ByteArray = reader.read(base + entry.valueAt, entry.byteCount.toInt())

    /**
     * Removes the location in place: the GPS directory is emptied and its values zeroed, and location fields in an
     * embedded XMP packet (the XMLPacket tag of RAW files) are blanked. Nothing else moves.
     */
    fun locationEdits(): List<Edit> {
        val ifd0 = ifd0()
        val edits = mutableListOf<Edit>()
        child(ifd0, GPS_IFD)?.let { gps ->
            gps.entries.filterNot { it.inline }.forEach { edits += Edit.zeros(base + it.valueAt, it.byteCount.toInt()) }
            edits += Edit.zeros(base + gps.offset, gps.byteCount)
        }
        ifd0.find(XML_PACKET)?.let { packet ->
            edits += Xmp.locationEdits(bytes(packet)).map { it.shiftedBy(base + packet.valueAt) }
        }
        return mergeZeros(edits)
    }

    /**
     * A new TIFF with only what *Remove all* keeps (docs/PLAN.md §6.3): orientation, colour space and the capture
     * date with its time zone. `null` if there's none of it.
     */
    fun minimal(): ByteArray? {
        val ifd0 = ifd0()
        val exif = child(ifd0, EXIF_IFD)
        val main = listOfNotNull(ifd0.find(ORIENTATION))
        val dates = exif?.let { ifd ->
            val original = CAPTURE_DATE_TAGS.mapNotNull(ifd::find)
            val kept = if (original.any {
                    it.tag == DATE_TIME_ORIGINAL
                }
            ) {
                original
            } else {
                DIGITIZED_TAGS.mapNotNull(ifd::find)
            }
            (kept + listOfNotNull(ifd.find(COLOR_SPACE))).sortedBy { it.tag }
        }.orEmpty()
        if (main.isEmpty() && dates.isEmpty()) return null
        return TiffWriter(order).apply {
            val ifd0Entries = main.map { it to bytes(it) } +
                if (dates.isEmpty()) emptyList() else listOf(null to ByteArray(0))
            write(ifd0Entries, dates.map { it to bytes(it) })
        }.toByteArray()
    }

    private fun buffer(offset: Long, length: Int): ByteBuffer =
        ByteBuffer.wrap(reader.read(base + offset, length)).order(order)

    companion object {
        const val GPS_IFD = 0x8825
        const val EXIF_IFD = 0x8769
        const val XML_PACKET = 0x02BC
        const val ORIENTATION = 0x0112
        const val DATE_TIME_ORIGINAL = 0x9003
        const val COLOR_SPACE = 0xA001
        val CAPTURE_DATE_TAGS = listOf(DATE_TIME_ORIGINAL, 0x9011, 0x9291) // Date, its UTC offset, sub-seconds.
        val DIGITIZED_TAGS = listOf(0x9004, 0x9012, 0x9292)

        const val HEADER_SIZE = 8
        const val ENTRY_SIZE = 12
        const val INLINE_BYTES = 4
        private const val MAGIC = 42
        private const val FIRST_IFD_AT = 4
        private const val VALUE_FIELD = 8
        private const val USHORT = 0xFFFF
        private const val UINT = 0xFFFFFFFFL
        private const val HEX = 16
        private val TYPE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

        fun typeSize(type: Int): Int = TYPE_SIZES.getOrNull(type)?.takeIf { it > 0 } ?: malformed("TIFF type $type")
    }
}

/** Writes a small TIFF: IFD0 and, when given, an EXIF IFD, values copied as they were. */
private class TiffWriter(private val order: ByteOrder) {
    private var out = ByteBuffer.allocate(0)

    /** In [main], a `null` entry stands for the pointer to the EXIF IFD, which holds [exif]. */
    fun write(main: List<Pair<Tiff.Entry?, ByteArray>>, exif: List<Pair<Tiff.Entry, ByteArray>>) {
        val ifd0Size = ifdSize(main.size)
        val exifAt = Tiff.HEADER_SIZE + ifd0Size
        val dataAt = exifAt + if (exif.isEmpty()) 0 else ifdSize(exif.size)
        val outOfLine = (main.mapNotNull { (entry, value) -> value.takeIf { entry != null } } + exif.map { it.second })
            .filter { it.size > Tiff.INLINE_BYTES }
        val total = dataAt + outOfLine.sumOf { it.size + it.size % 2 }
        out = ByteBuffer.allocate(total).order(order)
        out.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
        out.putShort(MAGIC).putInt(Tiff.HEADER_SIZE)
        var data = dataAt
        data = ifd(Tiff.HEADER_SIZE, main.map { (entry, value) -> entry to value }, data, exifAt)
        if (exif.isNotEmpty()) ifd(exifAt, exif.map { (entry, value) -> entry to value }, data, 0)
    }

    fun toByteArray(): ByteArray = out.array()

    /** Writes an IFD at [at] and its out-of-line values from [dataAt]; returns where the next values go. */
    private fun ifd(at: Int, entries: List<Pair<Tiff.Entry?, ByteArray>>, dataAt: Int, exifAt: Int): Int {
        var data = dataAt
        out.position(at)
        out.putShort(entries.size.toShort())
        val sorted = entries.sortedBy { (entry, _) -> entry?.tag ?: Tiff.EXIF_IFD }
        for ((entry, value) in sorted) {
            if (entry == null) {
                out.putShort(Tiff.EXIF_IFD.toShort()).putShort(TYPE_LONG).putInt(1).putInt(exifAt)
                continue
            }
            out.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count.toInt())
            if (value.size <= Tiff.INLINE_BYTES) {
                out.put(value.copyOf(Tiff.INLINE_BYTES))
            } else {
                out.putInt(data)
                val resume = out.position()
                out.position(data)
                out.put(value)
                data += value.size + value.size % 2
                out.position(resume)
            }
        }
        out.putInt(0)
        return data
    }

    private fun ifdSize(entries: Int) = 2 + entries * Tiff.ENTRY_SIZE + 4

    private companion object {
        const val MAGIC: Short = 42
        const val TYPE_LONG: Short = 4
    }
}

/** Sorts edits and joins overlapping or adjacent zero-fills into one, so they don't overlap. */
internal fun mergeZeros(edits: List<Edit>): List<Edit> {
    val sorted = edits.sortedBy { it.offset }
    val merged = mutableListOf<Edit>()
    for (edit in sorted) {
        val last = merged.lastOrNull()?.takeIf { it.isZeros() && edit.offset <= it.end }
        if (last != null && edit.isZeros()) {
            merged[merged.lastIndex] = Edit.zeros(last.offset, (maxOf(last.end, edit.end) - last.offset).toInt())
        } else {
            merged += edit
        }
    }
    return merged
}

private fun Edit.isZeros() = bytes.size.toLong() == length && bytes.all { it == 0.toByte() }
