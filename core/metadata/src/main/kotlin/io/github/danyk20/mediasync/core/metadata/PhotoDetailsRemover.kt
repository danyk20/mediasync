/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.model.PhotoDetails

/**
 * Decides how a photo or video is sent under a *Photo details* choice (docs/PLAN.md §6.3, §6.4): as it is, with
 * lossless edits to its metadata, or not at all.
 *
 * The format is read from the file's first bytes, not from its name or MIME type. A file whose details can't be
 * read is withheld rather than sent with them: the setting is a promise about what leaves the phone.
 */
object PhotoDetailsRemover {

    @Suppress("TooGenericExceptionCaught") // Reading untrusted bytes: a bounds error means a damaged file.
    fun plan(size: Long, reader: ByteReader, details: PhotoDetails): Redaction {
        if (details == PhotoDetails.KEEP_ALL || size == 0L) return Redaction.None
        return try {
            redaction(size, reader, all = details == PhotoDetails.REMOVE_ALL)
        } catch (e: RuntimeException) {
            // A damaged or contradictory file: its details can't be removed with certainty.
            if (UNREADABLE.any { it.isInstance(e) }) Redaction.Withheld(WithheldReason.UNSUPPORTED) else throw e
        }
    }

    private fun redaction(size: Long, reader: ByteReader, all: Boolean): Redaction {
        val remove = if (all) Isobmff.Remove.ALL else Isobmff.Remove.LOCATION
        return when (Format.of(reader.read(0, minOf(size, SNIFF_BYTES).toInt()))) {
            Format.JPEG -> edited(size, Jpeg(reader, 0, size).edits(all))

            Format.PNG -> edited(size, Png(reader, size).edits(all))

            Format.WEBP -> edited(size, WebP(reader, size).edits(all))

            Format.ISOBMFF -> Isobmff(reader, 0, size).let { file ->
                if (file.kind ==
                    Isobmff.Kind.RAW
                ) {
                    Redaction.Withheld(WithheldReason.RAW)
                } else {
                    edited(size, file.edits(remove))
                }
            }

            // RAW photos: their metadata is part of how the picture is stored (docs/PLAN.md §6.4).
            Format.TIFF -> if (all) {
                Redaction.Withheld(
                    WithheldReason.RAW,
                )
            } else {
                edited(size, Tiff(reader, 0, size).locationEdits())
            }

            Format.NO_DETAILS -> Redaction.None

            Format.UNKNOWN -> Redaction.Withheld(WithheldReason.UNSUPPORTED)
        }
    }

    private fun edited(size: Long, edits: List<Edit>): Redaction =
        if (edits.isEmpty()) Redaction.None else Redaction.Edited(mergeZeros(edits), size)

    /** What reading a damaged file throws; [Redaction.Edited] also rejects contradictory (overlapping) edits. */
    private val UNREADABLE = listOf(
        MalformedMediaException::class.java,
        IllegalArgumentException::class.java,
        IndexOutOfBoundsException::class.java,
        java.nio.BufferUnderflowException::class.java,
    )

    private const val SNIFF_BYTES = 16L
}

/** A photo format whose metadata MediaSync edits. */
internal interface Editor {
    /** *Remove location*. */
    fun locationEdits(): List<Edit>

    /** *Remove all*. */
    fun allEdits(): List<Edit>

    fun edits(all: Boolean): List<Edit> = if (all) allEdits() else locationEdits()
}

/** File formats by their first bytes. */
private enum class Format {
    JPEG,
    PNG,
    WEBP,
    ISOBMFF,
    TIFF,

    /** GIF and BMP, which can't hold a location. */
    NO_DETAILS,
    UNKNOWN,
    ;

    companion object {
        fun of(head: ByteArray): Format {
            fun at(offset: Int, text: String) = head.startsWith(text.latin1(), offset)
            return when {
                head.startsWith(JPEG_SIGNATURE) -> JPEG
                head.startsWith(PNG_SIGNATURE) -> PNG
                at(0, "RIFF") && at(8, "WEBP") -> WEBP
                ISOBMFF_FIRST_BOXES.any { at(4, it) } -> ISOBMFF
                at(0, "II*\u0000") || at(0, "MM\u0000*") -> TIFF
                at(0, "GIF8") || at(0, "BM") -> NO_DETAILS
                else -> UNKNOWN
            }
        }

        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val ISOBMFF_FIRST_BOXES = listOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot")
    }
}

/** Reads from an in-memory file. */
internal fun ByteArray.asReader() = ByteReader { offset, count ->
    if (offset < 0 || offset + count > size) malformed("Read of $count bytes at $offset past $size")
    copyOfRange(offset.toInt(), offset.toInt() + count)
}

/**
 * Whether a file of [mimeType] is withheld under [details], judged by its type alone: for counts shown before
 * anything is read. Sending reads the file itself ([PhotoDetailsRemover.plan]).
 */
fun isWithheld(mimeType: String, details: PhotoDetails): Boolean = when {
    details == PhotoDetails.KEEP_ALL -> false
    mimeType in RAW_TYPES -> details == PhotoDetails.REMOVE_ALL
    mimeType in EDITED_TYPES -> false
    else -> true
}

private val RAW_TYPES = setOf(
    "image/x-adobe-dng", "image/dng", "image/tiff", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-sony-arw",
    "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-fuji-raf",
)

private val EDITED_TYPES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/gif", "image/bmp",
    "video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2",
)
