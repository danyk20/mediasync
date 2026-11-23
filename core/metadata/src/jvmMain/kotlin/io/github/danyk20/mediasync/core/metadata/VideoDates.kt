/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.nio.ByteBuffer

/**
 * The capture time inside MP4, MOV and 3GP videos: the creation time of the movie header (`mvhd`), which Android's
 * media scanner and Google Photos read (docs/PLAN.md §6.2). Videos count seconds since 1904, in UTC; zero means
 * none, as in many videos saved by messaging apps.
 */
object VideoDates {

    /** The video's capture time, or `null` if it has none, or isn't a video MediaSync can read. */
    fun capturedAtMillis(size: Long, reader: ByteReader): Long? = readable {
        val header = video(size, reader)?.timeHeaders()?.firstOrNull { it.type == "mvhd" } ?: return@readable null
        times(reader, header).creation.takeIf { it != 0L }?.let { (it - EPOCH_1904_TO_1970) * MILLIS_PER_SECOND }
    }

    /**
     * Writes [capturedAtMillis] as the creation and modification times of the movie, its tracks and their media, where
     * they're zero. The file keeps its size and every byte of its samples (docs/PLAN.md §6.4). No edits for a video
     * that has a capture time, or that MediaSync can't read.
     */
    fun dateEdits(size: Long, reader: ByteReader, capturedAtMillis: Long): List<Edit> = readable {
        val file = video(size, reader) ?: return@readable emptyList()
        val headers = file.timeHeaders()
        if (headers.none { it.type == "mvhd" } || capturedAtMillis(size, reader) != null) return@readable emptyList()
        val seconds = capturedAtMillis / MILLIS_PER_SECOND + EPOCH_1904_TO_1970
        headers.flatMap { header -> edits(reader, header, seconds) }
    }.orEmpty()

    private fun video(size: Long, reader: ByteReader): Isobmff? {
        if (size < SNIFF_BYTES || String(reader.read(4, 4), Charsets.ISO_8859_1) !in FIRST_BOXES) return null
        return Isobmff(reader, 0, size).takeIf { it.kind == Isobmff.Kind.VIDEO }
    }

    private class Times(val version: Int, val creation: Long, val modification: Long)

    /** Version 0 headers keep 32-bit times after their version and flags; version 1, 64-bit ones. */
    private fun times(reader: ByteReader, header: Isobmff.Box): Times {
        val data = ByteBuffer.wrap(reader.read(header.payloadAt, minOf(header.payloadSize, V1_TIMES).toInt()))
        return when (val version = data.get(0).toInt()) {
            0 -> Times(0, data.getInt(4).toLong() and UINT, data.getInt(8).toLong() and UINT)
            1 -> Times(1, data.getLong(4), data.getLong(12))
            else -> malformed("Header version $version")
        }
    }

    private fun edits(reader: ByteReader, header: Isobmff.Box, seconds: Long): List<Edit> {
        val times = times(reader, header)
        if (times.version == 0 && seconds > UINT) return emptyList() // After 2040: doesn't fit.
        val width = if (times.version == 0) Int.SIZE_BYTES else Long.SIZE_BYTES
        val value = ByteBuffer.allocate(width).apply {
            if (width == Int.SIZE_BYTES) putInt(seconds.toInt()) else putLong(seconds)
        }.array()
        return listOfNotNull(
            Edit.overwrite(header.payloadAt + 4, value).takeIf { times.creation == 0L },
            Edit.overwrite(header.payloadAt + 4 + width, value).takeIf { times.modification == 0L },
        )
    }

    /** `null` for a damaged file. */
    @Suppress("TooGenericExceptionCaught") // Reading untrusted bytes: a bounds error means a damaged file.
    private fun <T> readable(read: () -> T): T? = try {
        read()
    } catch (e: RuntimeException) {
        if (UNREADABLE.any { it.isInstance(e) }) null else throw e
    }

    private val UNREADABLE = listOf(
        MalformedMediaException::class.java,
        IllegalArgumentException::class.java,
        IndexOutOfBoundsException::class.java,
        java.nio.BufferUnderflowException::class.java,
    )
    private val FIRST_BOXES = setOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot")
    private const val SNIFF_BYTES = 8L
    private const val V1_TIMES = 20L
    private const val UINT = 0xFFFFFFFFL
    private const val MILLIS_PER_SECOND = 1_000L

    /** Seconds from 1904-01-01 to 1970-01-01, both UTC. */
    private const val EPOCH_1904_TO_1970 = 2_082_844_800L
}
