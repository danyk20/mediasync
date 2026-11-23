/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

/** The byte order of a [ByteBuffer]'s numbers. */
internal enum class ByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }

/**
 * The few operations of the JDK's byte buffer that this module uses, over a [ByteArray], with the same meaning: fixed
 * capacity, a position for relative operations, absolute getters, big-endian until [order] says otherwise.
 * Reading outside the array throws [IndexOutOfBoundsException] (the JDK's buffer threw `BufferUnderflowException` for
 * relative reads; callers treat both as "damaged file"); writing outside throws [IllegalStateException] (the JDK's:
 * `BufferOverflowException`).
 */
@Suppress("TooManyFunctions") // Mirrors the JDK buffer API this code was written against.
internal class ByteBuffer private constructor(private val bytes: ByteArray) {
    private var position = 0
    private var byteOrder = ByteOrder.BIG_ENDIAN

    fun order(order: ByteOrder): ByteBuffer = apply { byteOrder = order }

    fun capacity(): Int = bytes.size

    fun array(): ByteArray = bytes

    fun position(): Int = position

    fun position(newPosition: Int): ByteBuffer {
        require(newPosition in 0..bytes.size) { "Position $newPosition of ${bytes.size}" }
        position = newPosition
        return this
    }

    fun get(index: Int): Byte = bytes[checked(index, 1)]

    fun getShort(index: Int): Short = read(checked(index, Short.SIZE_BYTES), Short.SIZE_BYTES).toShort()

    fun getInt(index: Int): Int = read(checked(index, Int.SIZE_BYTES), Int.SIZE_BYTES).toInt()

    fun getLong(index: Int): Long = read(checked(index, Long.SIZE_BYTES), Long.SIZE_BYTES)

    /** Relative reads: from the position, which moves on. */
    fun getShort(): Short = getShort(next(Short.SIZE_BYTES))

    fun getInt(): Int = getInt(next(Int.SIZE_BYTES))

    fun getLong(): Long = getLong(next(Long.SIZE_BYTES))

    fun put(value: Byte): ByteBuffer = apply { bytes[claim(1)] = value }

    fun put(source: ByteArray): ByteBuffer = apply { source.copyInto(bytes, claim(source.size)) }

    fun putShort(value: Short): ByteBuffer = apply { write(claim(Short.SIZE_BYTES), value.toLong(), Short.SIZE_BYTES) }

    fun putInt(value: Int): ByteBuffer = apply { write(claim(Int.SIZE_BYTES), value.toLong(), Int.SIZE_BYTES) }

    fun putLong(value: Long): ByteBuffer = apply { write(claim(Long.SIZE_BYTES), value, Long.SIZE_BYTES) }

    fun putInt(index: Int, value: Int): ByteBuffer =
        apply { write(checked(index, Int.SIZE_BYTES), value.toLong(), Int.SIZE_BYTES) }

    private fun checked(index: Int, size: Int): Int {
        if (index < 0 ||
            index > bytes.size - size
        ) {
            throw IndexOutOfBoundsException("$size bytes at $index of ${bytes.size}")
        }
        return index
    }

    private fun next(size: Int): Int {
        if (size > bytes.size - position) throw IndexOutOfBoundsException("$size bytes at $position of ${bytes.size}")
        return position.also { position += size }
    }

    private fun claim(size: Int): Int {
        check(size <= bytes.size - position) { "Buffer overflow: $size bytes at $position of ${bytes.size}" }
        return position.also { position += size }
    }

    private fun read(at: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until size) {
            val b = bytes[at + if (byteOrder == ByteOrder.BIG_ENDIAN) i else size - 1 - i].toLong() and BYTE_MASK
            value = (value shl BYTE_BITS) or b
        }
        return value
    }

    private fun write(at: Int, value: Long, size: Int) {
        for (i in 0 until size) {
            val shift = BYTE_BITS * if (byteOrder == ByteOrder.BIG_ENDIAN) size - 1 - i else i
            bytes[at + i] = (value ushr shift).toByte()
        }
    }

    companion object {
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFFL

        fun allocate(capacity: Int): ByteBuffer {
            require(capacity >= 0) { "Capacity $capacity" }
            return ByteBuffer(ByteArray(capacity))
        }

        fun wrap(array: ByteArray): ByteBuffer = ByteBuffer(array)
    }
}
