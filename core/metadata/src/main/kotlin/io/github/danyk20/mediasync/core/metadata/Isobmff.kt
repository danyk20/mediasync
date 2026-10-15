/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.nio.ByteBuffer

/**
 * An ISO base media file from [start] to [end]: MP4, MOV and 3GP videos, HEIC and AVIF photos.
 *
 * Every edit keeps the file's size: removed boxes become `free` boxes full of zeros, and HEIC metadata items are
 * rewritten in place. Videos point to their samples by absolute file offsets, which therefore stay valid without
 * re-muxing (docs/PLAN.md §6.4). An [embedded] file (a Motion Photo's video) may be followed by other data.
 */
internal class Isobmff(
    private val reader: ByteReader,
    private val start: Long,
    private val end: Long,
    private val embedded: Boolean = false,
) {
    enum class Kind { VIDEO, HEIF, RAW }

    enum class Remove { LOCATION, ALL }

    class Box(val type: String, val at: Long, val headerSize: Int, val end: Long) {
        val payloadAt: Long get() = at + headerSize
        val payloadSize: Long get() = end - payloadAt
    }

    private val top: List<Box> = children(start, end, lenient = embedded)

    val kind: Kind = run {
        val ftyp = top.firstOrNull()?.takeIf { it.type == "ftyp" } ?: return@run Kind.VIDEO // Old QuickTime.
        val brands = reader.read(ftyp.payloadAt, minOf(ftyp.payloadSize, MAX_BRANDS).toInt())
            .let { bytes -> (0 until bytes.size / 4).map { String(bytes, it * 4, 4, Charsets.ISO_8859_1) } }
            .filterIndexed { i, _ -> i != 1 } // Index 1 is the minor version.
        when {
            brands.any { it in RAW_BRANDS } -> Kind.RAW
            brands.firstOrNull() in HEIF_BRANDS -> Kind.HEIF
            else -> Kind.VIDEO
        }
    }

    fun edits(remove: Remove): List<Edit> = when (kind) {
        Kind.HEIF -> heifEdits(remove)
        Kind.VIDEO -> videoEdits(remove)
        Kind.RAW -> malformed("RAW photos aren't edited")
    }

    /** Video: location boxes (*Remove location*), or user data and metadata boxes (*Remove all*). */
    fun videoEdits(remove: Remove): List<Edit> = top.flatMap { box ->
        when (box.type) {
            "moov" -> children(box).flatMap { child ->
                when (child.type) {
                    "trak" -> children(child).flatMap { trackChild -> metadata(trackChild, remove) }
                    else -> metadata(child, remove)
                }
            }

            else -> metadata(box, remove)
        }
    }

    private fun metadata(box: Box, remove: Remove): List<Edit> = when (box.type) {
        "udta" -> if (remove == Remove.ALL) free(box) else userDataLocation(box)
        "meta" -> meta(box, remove)
        "uuid" -> xmpBox(box, remove)
        else -> emptyList()
    }

    private fun userDataLocation(udta: Box): List<Edit> = children(udta, lenient = true).flatMap { child ->
        when (child.type) {
            "©xyz", "loci" -> free(child)
            "meta" -> meta(child, Remove.LOCATION)
            "XMP_" -> xmp(child.payloadAt, child.payloadSize, Remove.LOCATION)
            else -> emptyList()
        }
    }

    /** A metadata box: Apple-style keys (`mdta`), or iTunes-style items. */
    private fun meta(meta: Box, remove: Remove): List<Edit> {
        // ISO `meta` boxes start with a version and flags; QuickTime ones don't.
        val quickTime = meta.payloadSize >= 8 && type(meta.payloadAt + 4) == "hdlr"
        val boxes = children(meta.payloadAt + if (quickTime) 0 else 4, meta.end, lenient = true)
        val handler = boxes.firstOrNull { it.type == "hdlr" }?.let { type(it.payloadAt + HANDLER_TYPE) }
        val ilst = boxes.firstOrNull { it.type == "ilst" }
        return when {
            handler == "mdta" -> keyed(boxes, ilst, remove)
            remove == Remove.ALL -> free(meta)
            ilst != null -> children(ilst, lenient = true).filter { it.type == "©xyz" }.flatMap(::free)
            else -> emptyList()
        }
    }

    /** Apple-style metadata: a list of key names and, in `ilst`, values numbered after them. */
    private fun keyed(boxes: List<Box>, ilst: Box?, remove: Remove): List<Edit> {
        val keys = boxes.firstOrNull { it.type == "keys" } ?: return emptyList()
        val payload = ByteBuffer.wrap(reader.read(keys.payloadAt, keys.payloadSize.toInt()))
        val count = payload.getInt(4)
        val edits = mutableListOf<Edit>()
        val removed = mutableSetOf<Int>()
        var at = 8
        for (index in 1..count) {
            if (at + 8 > payload.capacity()) malformed("Metadata keys")
            val size = payload.getInt(at)
            if (size < 8 || at + size > payload.capacity()) malformed("Metadata key of $size bytes")
            val name = String(payload.array(), at + 8, size - 8, Charsets.ISO_8859_1)
            val keep = when (remove) {
                Remove.ALL -> name in KEPT_KEYS
                Remove.LOCATION -> !name.contains("location", ignoreCase = true)
            }
            if (!keep) {
                removed += index
                edits += Edit.zeros(keys.payloadAt + at + 8, size - 8)
            }
            at += size
        }
        ilst?.let { list ->
            children(list, lenient = true)
                .filter { ByteBuffer.wrap(it.type.latin1()).int in removed }
                .forEach { edits += free(it) }
        }
        return edits
    }

    /** An XMP packet in a `uuid` box. */
    private fun xmpBox(box: Box, remove: Remove): List<Edit> {
        if (box.payloadSize < XMP_UUID.size || !reader.read(box.payloadAt, XMP_UUID.size).contentEquals(XMP_UUID)) {
            return emptyList()
        }
        if (remove == Remove.ALL) return free(box)
        return xmp(box.payloadAt + XMP_UUID.size, box.payloadSize - XMP_UUID.size, remove)
    }

    private fun xmp(at: Long, size: Long, remove: Remove): List<Edit> = when (remove) {
        Remove.LOCATION -> Xmp.locationEdits(reader.read(at, size.toInt())).map { it.shiftedBy(at) }
        Remove.ALL -> listOf(Edit.overwrite(at, Xmp.emptyPacket(size.toInt())))
    }

    /** HEIC and AVIF: the EXIF and XMP items, rewritten in place, and a Motion Photo's video after the photo. */
    private fun heifEdits(remove: Remove): List<Edit> = mergeZeros(itemEdits(remove) + motionVideoEdits(remove))

    private fun itemEdits(remove: Remove): List<Edit> {
        val meta = top.firstOrNull { it.type == "meta" } ?: return emptyList()
        val boxes = children(meta.payloadAt + 4, meta.end)
        val idat = boxes.firstOrNull { it.type == "idat" }
        val locations = boxes.firstOrNull { it.type == "iloc" }?.let(::itemLocations).orEmpty()
        val infos = boxes.firstOrNull { it.type == "iinf" }?.let(::itemInfos).orEmpty()
        return infos.flatMap { (id, info) ->
            val item = locations[id]?.let { Item(it, idat) }
            when {
                item == null -> emptyList()
                info.type == "Exif" -> item.map(exifItemEdits(item, remove))
                info.isXmp -> item.map(xmpItemEdits(item, remove))
                else -> emptyList()
            }
        }
    }

    /** A Motion Photo's video: appended after the photo (Google), or in an `mpvd` box (Samsung). */
    private fun motionVideoEdits(remove: Remove): List<Edit> {
        val video = top.drop(1).firstOrNull { it.type == "ftyp" || it.type == "mpvd" } ?: return emptyList()
        if (remove == Remove.ALL) return listOf(Edit.delete(video.at, end - video.at))
        return if (video.type == "mpvd") {
            Isobmff(reader, video.payloadAt, video.end, embedded = true).videoEdits(Remove.LOCATION)
        } else {
            Isobmff(reader, video.at, end, embedded = true).videoEdits(Remove.LOCATION)
        }
    }

    private fun exifItemEdits(item: Item, remove: Remove): List<Edit> {
        if (item.size < 4) malformed("EXIF item of ${item.size} bytes")
        val tiffAt = 4 + (ByteBuffer.wrap(item.read(0, 4)).int.toLong() and UINT)
        val tiff = Tiff(item::read, tiffAt, item.size - tiffAt)
        return when (remove) {
            Remove.LOCATION -> tiff.locationEdits()

            Remove.ALL -> {
                val space = (item.size - tiffAt).toInt()
                val minimal = tiff.minimal() ?: EMPTY_TIFF(tiff.order)
                if (minimal.size > space) malformed("No room for the kept EXIF")
                listOf(Edit.overwrite(tiffAt, minimal + ByteArray(space - minimal.size)))
            }
        }
    }

    private fun xmpItemEdits(item: Item, remove: Remove): List<Edit> = when (remove) {
        Remove.LOCATION -> Xmp.locationEdits(item.read(0, item.size.toInt()))
        Remove.ALL -> listOf(Edit.overwrite(0, Xmp.emptyPacket(item.size.toInt())))
    }

    private class ItemInfo(val type: String, val contentType: String?) {
        val isXmp: Boolean get() = type == "mime" && contentType == "application/rdf+xml"
    }

    private fun itemInfos(iinf: Box): Map<Int, ItemInfo> {
        val version = reader.read(iinf.payloadAt, 1)[0].toInt()
        val entriesAt = iinf.payloadAt + 4 + if (version == 0) 2 else 4
        return children(entriesAt, iinf.end).filter { it.type == "infe" }.mapNotNull { infe ->
            val data = reader.read(infe.payloadAt, infe.payloadSize.toInt())
            val infeVersion = data[0].toInt()
            if (infeVersion < 2) return@mapNotNull null // Pre-HEIF item entries carry no item type.
            val buffer = ByteBuffer.wrap(data)
            val idSize = if (infeVersion == 2) 2 else 4
            val id = if (idSize == 2) buffer.getShort(4).toInt() and USHORT else buffer.getInt(4)
            val typeAt = 4 + idSize + 2
            val type = String(data, typeAt, 4, Charsets.ISO_8859_1)
            val nameEnd = data.indexOfZero(typeAt + 4)
            val contentType = if (type == "mime" && nameEnd >= 0) {
                val typeEnd = data.indexOfZero(nameEnd + 1).takeIf { it >= 0 } ?: data.size
                String(data, nameEnd + 1, typeEnd - nameEnd - 1, Charsets.ISO_8859_1)
            } else {
                null
            }
            id to ItemInfo(type, contentType)
        }.toMap()
    }

    private class ItemLocation(val method: Int, val extents: List<Pair<Long, Long>>)

    private fun itemLocations(iloc: Box): Map<Int, ItemLocation> {
        val data = ByteBuffer.wrap(reader.read(iloc.payloadAt, iloc.payloadSize.toInt()))
        val version = data.get(0).toInt()
        val sizes = data.get(4).toInt()
        val offsetSize = (sizes shr NIBBLE) and NIBBLE_MASK
        val lengthSize = sizes and NIBBLE_MASK
        val baseSizes = data.get(5).toInt()
        val baseOffsetSize = (baseSizes shr NIBBLE) and NIBBLE_MASK
        val indexSize = if (version == 1 || version == 2) baseSizes and NIBBLE_MASK else 0
        data.position(6)
        val count = if (version < 2) data.short.toInt() and USHORT else data.int
        return (0 until count).associate {
            val id = if (version < 2) data.short.toInt() and USHORT else data.int
            val method = if (version == 1 || version == 2) data.short.toInt() and NIBBLE_MASK else 0
            data.short // Data reference index.
            val base = data.number(baseOffsetSize)
            val extents = List(data.short.toInt() and USHORT) {
                data.number(indexSize)
                (base + data.number(offsetSize)) to data.number(lengthSize)
            }
            id to ItemLocation(method, extents)
        }
    }

    /** An item's bytes: its extents in the file, or in `idat` for construction method 1. */
    private inner class Item(location: ItemLocation, idat: Box?) {
        private val ranges: List<Pair<Long, Long>> = location.extents.map { (offset, length) ->
            when (location.method) {
                0 -> offset to if (length == 0L) end - offset else length

                1 -> {
                    val box = idat ?: malformed("Item in a missing idat")
                    (box.payloadAt + offset) to if (length == 0L) box.payloadSize - offset else length
                }

                else -> malformed("Item construction method ${location.method}")
            }
        }

        val size: Long = ranges.sumOf { it.second }

        init {
            if (ranges.any { (offset, length) -> offset < 0 || offset + length > end }) malformed("Item past the end")
        }

        fun read(offset: Long, byteCount: Int): ByteArray {
            val out = ByteArray(byteCount)
            var filled = 0
            var itemAt = 0L
            for ((at, length) in ranges) {
                val from = maxOf(offset + filled, itemAt)
                val to = minOf(offset + byteCount, itemAt + length)
                if (from < to) {
                    System.arraycopy(
                        reader.read(at + from - itemAt, (to - from).toInt()),
                        0,
                        out,
                        filled,
                        (to - from).toInt(),
                    )
                    filled += (to - from).toInt()
                }
                itemAt += length
            }
            if (filled != byteCount) malformed("Read past the end of an item")
            return out
        }

        /** [edits], relative to the item's first byte, as edits of the file (split where the item is). */
        fun map(edits: List<Edit>): List<Edit> = edits.flatMap { edit ->
            if (edit.bytes.size.toLong() != edit.length) malformed("Items are only overwritten")
            var itemAt = 0L
            ranges.mapNotNull { (at, length) ->
                val from = maxOf(edit.offset, itemAt)
                val to = minOf(edit.end, itemAt + length)
                val part = if (from < to) {
                    val bytes = edit.bytes.copyOfRange((from - edit.offset).toInt(), (to - edit.offset).toInt())
                    Edit.overwrite(at + from - itemAt, bytes)
                } else {
                    null
                }
                itemAt += length
                part
            }
        }
    }

    /** Turns [box] into a `free` box of zeros: same size, nothing left in it. */
    private fun free(box: Box): List<Edit> =
        listOf(Edit.overwrite(box.at + 4, FREE), Edit.zeros(box.payloadAt, box.payloadSize.toInt()))

    private fun children(box: Box, lenient: Boolean = false) = children(box.payloadAt, box.end, lenient)

    /**
     * The boxes from [from] to [to]. [lenient] stops at the first one that doesn't make sense (data that follows an
     * embedded video, or the 4 zero bytes QuickTime may end user data with).
     */
    private fun children(from: Long, to: Long, lenient: Boolean = false): List<Box> {
        val boxes = mutableListOf<Box>()
        var at = from
        while (at + BOX_HEADER <= to) {
            val box = boxAt(at, to) ?: if (lenient) break else malformed("Box at $at")
            boxes += box
            at = box.end
        }
        if (!lenient && to - at >= BOX_HEADER) malformed("Data after the boxes at $at")
        return boxes
    }

    /** The box at [at], if it fits before [to]. */
    private fun boxAt(at: Long, to: Long): Box? {
        val header = ByteBuffer.wrap(reader.read(at, BOX_HEADER))
        val type = String(header.array(), 4, 4, Charsets.ISO_8859_1)
        val (size, headerSize) = when (val size = header.getInt(0).toLong() and UINT) {
            0L -> (to - at) to BOX_HEADER

            1L -> if (at + LARGE_HEADER >
                to
            ) {
                return null
            } else {
                ByteBuffer.wrap(reader.read(at + BOX_HEADER, 8)).long to LARGE_HEADER
            }

            else -> size to BOX_HEADER
        }
        return Box(type, at, headerSize, at + size).takeIf { size >= headerSize && at + size <= to }
    }

    private fun type(at: Long) = String(reader.read(at, 4), Charsets.ISO_8859_1)

    private fun ByteBuffer.number(size: Int): Long = when (size) {
        0 -> 0L
        4 -> int.toLong() and UINT
        8 -> long
        else -> malformed("Number of $size bytes")
    }

    private fun ByteArray.indexOfZero(from: Int): Int = (from until size).firstOrNull { this[it] == 0.toByte() } ?: -1

    private companion object {
        const val BOX_HEADER = 8
        const val LARGE_HEADER = 16
        const val HANDLER_TYPE = 8
        const val MAX_BRANDS = 64L
        const val NIBBLE = 4
        const val NIBBLE_MASK = 0xF
        const val USHORT = 0xFFFF
        const val UINT = 0xFFFFFFFFL
        const val HEX = 16
        val FREE = "free".latin1()

        /** Adobe's XMP `uuid`: BE7ACFCB-97A9-42E8-9C71-999491E3AFAC. */
        val XMP_UUID = "BE7ACFCB97A942E89C71999491E3AFAC".chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()

        val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "mif2", "avif", "avis")
        val RAW_BRANDS = setOf("crx ")

        /** Not personal, and slow-motion videos play at the wrong speed without it. */
        val KEPT_KEYS = setOf("com.android.capture.fps")

        /** A TIFF with an empty first directory, in [order]. */
        val EMPTY_TIFF: (java.nio.ByteOrder) -> ByteArray = { order ->
            ByteBuffer.allocate(Tiff.HEADER_SIZE + 2 + 4).order(order)
                .put((if (order == java.nio.ByteOrder.LITTLE_ENDIAN) "II" else "MM").latin1())
                .putShort(42).putInt(Tiff.HEADER_SIZE).putShort(0).putInt(0).array()
        }
    }
}
