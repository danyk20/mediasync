/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

/** The bytes of this text in ISO-8859-1; characters beyond it become `?` as `toByteArray(Charsets.ISO_8859_1)` did. */
internal fun String.latin1(): ByteArray =
    ByteArray(length) { i -> this[i].let { if (it.code > LATIN1_MAX) '?'.code.toByte() else it.code.toByte() } }

/** [length] bytes from [start] as text, one character per byte (ISO-8859-1). */
internal fun ByteArray.latin1String(start: Int = 0, length: Int = size - start): String {
    if (start < 0 || length < 0 ||
        start > size - length
    ) {
        throw IndexOutOfBoundsException("$length bytes at $start of $size")
    }
    return CharArray(length) { (this[start + it].toInt() and LATIN1_MAX).toChar() }.concatToString()
}

private const val LATIN1_MAX = 0xFF
