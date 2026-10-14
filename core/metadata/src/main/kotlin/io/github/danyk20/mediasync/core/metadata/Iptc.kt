/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Photoshop image resources (a JPEG's APP13 segment), where IPTC captions, keywords and place names live. Desktop
 * photo tools write them; cameras rarely do.
 */
internal object PhotoshopResources {
    val SIGNATURE = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1)

    /** [payload] (an APP13 segment's) without IPTC location datasets; `null` when there's nothing to remove. */
    fun withoutLocation(payload: ByteArray): ByteArray? {
        if (!payload.startsWith(SIGNATURE)) return null
        val out = ByteArrayOutputStream().apply { write(SIGNATURE) }
        var changed = false
        var i = SIGNATURE.size
        while (i < payload.size) {
            if (i + RESOURCE_HEADER > payload.size || !payload.startsWith(RESOURCE, i)) malformed("Photoshop resource")
            val id = ByteBuffer.wrap(payload, i + 4, 2).short.toInt() and USHORT
            val nameLength = payload[i + 6].toInt() and BYTE
            val nameField = (1 + nameLength).let { it + it % 2 }
            val sizeAt = i + 6 + nameField
            if (sizeAt + 4 > payload.size) malformed("Photoshop resource name")
            val size = ByteBuffer.wrap(payload, sizeAt, 4).int
            val dataAt = sizeAt + 4
            if (size < 0 || dataAt + size > payload.size) malformed("Photoshop resource of $size bytes")
            val data = payload.copyOfRange(dataAt, dataAt + size)
            val kept = if (id == IPTC) Iptc.withoutLocation(data) else data
            changed = changed || kept.size != data.size
            out.write(payload, i, sizeAt - i)
            out.write(ByteBuffer.allocate(4).putInt(kept.size).array())
            out.write(kept)
            if (kept.size % 2 == 1) out.write(0)
            i = dataAt + size + size % 2
        }
        return if (changed) out.toByteArray() else null
    }

    private val RESOURCE = "8BIM".toByteArray(Charsets.ISO_8859_1)
    private const val IPTC = 0x0404
    private const val RESOURCE_HEADER = 12
    private const val USHORT = 0xFFFF
    private const val BYTE = 0xFF
}

/** IPTC-IIM datasets: `0x1C`, record, dataset number, length, value. */
internal object Iptc {
    /** City, sublocation, province or state, country code and name, content location code and name. */
    private val LOCATION = setOf(26, 27, 90, 92, 95, 100, 101)
    private const val APPLICATION_RECORD = 2
    private const val TAG_MARKER = 0x1C
    private const val EXTENDED = 0x8000
    private const val HEADER = 5

    fun withoutLocation(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            if (data[i].toInt() != TAG_MARKER) {
                if (data.drop(i).all { it == 0.toByte() }) break // Padding.
                malformed("IPTC dataset marker")
            }
            if (i + HEADER > data.size) malformed("IPTC dataset header")
            val record = data[i + 1].toInt() and 0xFF
            val dataset = data[i + 2].toInt() and 0xFF
            var length = ((data[i + 3].toInt() and 0xFF) shl 8) or (data[i + 4].toInt() and 0xFF)
            var valueAt = i + HEADER
            if (length and EXTENDED != 0) {
                val lengthBytes = length and EXTENDED.inv()
                if (lengthBytes > 4 || valueAt + lengthBytes > data.size) malformed("IPTC extended length")
                length = (0 until lengthBytes).fold(0) { acc, k -> (acc shl 8) or (data[valueAt + k].toInt() and 0xFF) }
                valueAt += lengthBytes
            }
            val end = valueAt + length
            if (length < 0 || end > data.size) malformed("IPTC dataset of $length bytes")
            if (!(record == APPLICATION_RECORD && dataset in LOCATION)) out.write(data, i, end - i)
            i = end
        }
        return out.toByteArray()
    }
}

internal fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
    at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }
