/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import okio.Buffer
import okio.EOFException
import okio.IOException
import okio.Source
import okio.Timeout
import okio.buffer

/** Reads [byteCount] bytes of a file starting at [offset]. Must return exactly that many bytes. */
fun interface ByteReader {
    fun read(offset: Long, byteCount: Int): ByteArray
}

/** One change to a file: the [length] bytes at [offset] become [bytes] (fewer, more or none at all). */
class Edit(val offset: Long, val length: Long, val bytes: ByteArray = ByteArray(0)) {
    init {
        require(offset >= 0 && length >= 0) { "Edit at $offset of $length bytes" }
    }

    val end: Long get() = offset + length

    override fun equals(other: Any?): Boolean =
        other is Edit && offset == other.offset && length == other.length && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (offset.hashCode() * HASH + length.hashCode()) * HASH + bytes.contentHashCode()

    override fun toString(): String = "Edit(offset=$offset, length=$length, bytes=${bytes.size})"

    internal fun shiftedBy(delta: Long) = Edit(offset + delta, length, bytes)

    companion object {
        private const val HASH = 31

        /** Overwrites [length] bytes at [offset] with zeros: the file keeps its size. */
        fun zeros(offset: Long, length: Int) = Edit(offset, length.toLong(), ByteArray(length))

        /** Overwrites the bytes at [offset] with [bytes]: the file keeps its size. */
        fun overwrite(offset: Long, bytes: ByteArray) = Edit(offset, bytes.size.toLong(), bytes)

        /** Removes [length] bytes at [offset]. */
        fun delete(offset: Long, length: Long) = Edit(offset, length)
    }
}

/** Why a file isn't sent under the chosen *Photo details* (docs/PLAN.md §6.4). */
enum class WithheldReason {
    /** A RAW photo: its details are part of how the picture is stored, so *Remove all* can't take them out. */
    RAW,

    /** A format whose details MediaSync can't read, or a damaged file: it can't promise they're gone. */
    UNSUPPORTED,
}

/** How a file is sent under a *Photo details* choice. */
sealed interface Redaction {
    /** Sent as it is. */
    data object None : Redaction

    /**
     * Sent with [edits] applied: sorted, non-overlapping changes to metadata only. Image and video data are never
     * touched, so nothing is re-encoded and the picture stays exactly the same.
     */
    class Edited(val edits: List<Edit>, val inputSize: Long) : Redaction {
        init {
            edits.zipWithNext().forEach { (a, b) -> require(a.end <= b.offset) { "Overlapping edits: $a, $b" } }
            require(edits.all { it.end <= inputSize }) { "Edit beyond the end of the file" }
        }

        val outputSize: Long = inputSize + edits.sumOf { it.bytes.size - it.length }

        /** The edited file, read from [original], which must start at its first byte. */
        fun open(original: Source): Source = EditedSource(original, edits)

        override fun equals(other: Any?): Boolean =
            other is Edited && edits == other.edits && inputSize == other.inputSize

        override fun hashCode(): Int = edits.hashCode() * HASH_PRIME + inputSize.hashCode()

        override fun toString(): String = "Edited(${edits.size} edits, $inputSize -> $outputSize bytes)"

        private companion object {
            const val HASH_PRIME = 31
        }
    }

    /** Not sent: see [reason]. */
    data class Withheld(val reason: WithheldReason) : Redaction
}

/** Applies sorted, non-overlapping [edits] while [original] streams through. */
private class EditedSource(original: Source, private val edits: List<Edit>) : Source {
    private val upstream = original.buffer()

    /** Position in the original. */
    private var position = 0L
    private var next = 0

    /** Replacement bytes of the edit at [position] still to return. */
    private var pending: ByteArray? = null
    private var pendingAt = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        while (true) {
            pending?.let { bytes ->
                if (pendingAt < bytes.size) {
                    val n = minOf(byteCount, (bytes.size - pendingAt).toLong()).toInt()
                    sink.write(bytes, pendingAt, n)
                    pendingAt += n
                    return n.toLong()
                }
                pending = null
            }
            val edit = edits.getOrNull(next)
            if (edit != null && edit.offset == position) {
                skipOriginal(edit.length)
                next++
                pending = edit.bytes
                pendingAt = 0
                continue
            }
            val limit = if (edit == null) byteCount else minOf(byteCount, edit.offset - position)
            val n = upstream.read(sink, limit)
            if (n > 0) position += n
            return n
        }
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() = upstream.close()

    private fun skipOriginal(count: Long) {
        try {
            upstream.skip(count)
        } catch (_: EOFException) {
            throw IOException("The file ended inside an edit")
        }
        position += count
    }
}
