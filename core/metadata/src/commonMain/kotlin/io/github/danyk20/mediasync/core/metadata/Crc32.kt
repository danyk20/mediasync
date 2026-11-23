/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

/** The CRC-32 of PNG, zlib and ZIP (reflected polynomial 0xEDB88320) as an unsigned value. */
internal fun crc32(bytes: ByteArray): Long {
    var crc = -1
    for (b in bytes) crc = CRC_TABLE[(crc xor b.toInt()) and BYTE] xor (crc ushr BYTE_BITS)
    return crc.inv().toLong() and UINT
}

private const val BYTE = 0xFF
private const val BYTE_BITS = 8
private const val UINT = 0xFFFFFFFFL
private const val POLYNOMIAL = 0xEDB88320

private val CRC_TABLE = IntArray(BYTE + 1) { n ->
    var c = n
    repeat(BYTE_BITS) { c = if (c and 1 != 0) (c ushr 1) xor POLYNOMIAL.toInt() else c ushr 1 }
    c
}
