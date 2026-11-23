/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

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
        fun open(original: InputStream): InputStream = EditedInputStream(original, edits)

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
private class EditedInputStream(original: InputStream, private val edits: List<Edit>) : FilterInputStream(original) {
    /** Position in the original. */
    private var position = 0L
    private var next = 0

    /** Replacement bytes of the edit at [position] still to return. */
    private var pending: ByteArray? = null
    private var pendingAt = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        pending?.let { return readPending(buffer, offset, length) }
        val edit = edits.getOrNull(next)
        if (edit != null && edit.offset == position) {
            skipOriginal(edit.length)
            next++
            pending = edit.bytes
            pendingAt = 0
            return read(buffer, offset, length)
        }
        val limit = if (edit == null) length else minOf(length.toLong(), edit.offset - position).toInt()
        val n = super.read(buffer, offset, limit)
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        var skipped = 0L
        val scratch = ByteArray(SKIP_BUFFER)
        while (skipped < n) {
            val read = read(scratch, 0, minOf(scratch.size.toLong(), n - skipped).toInt())
            if (read == -1) break
            skipped += read
        }
        return skipped
    }

    override fun markSupported() = false

    private fun readPending(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = pending!!
        if (pendingAt == bytes.size) {
            pending = null
            return read(buffer, offset, length)
        }
        val n = minOf(length, bytes.size - pendingAt)
        System.arraycopy(bytes, pendingAt, buffer, offset, n)
        pendingAt += n
        return n
    }

    private fun skipOriginal(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = `in`.skip(remaining)
            if (skipped <= 0) {
                if (`in`.read() == -1) throw IOException("The file ended inside an edit")
                remaining--
            } else {
                remaining -= skipped
            }
        }
        position += count
    }

    private companion object {
        const val BYTE = 0xFF
        const val SKIP_BUFFER = 64 * 1024
    }
}
