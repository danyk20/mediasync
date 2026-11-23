/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import okio.Buffer
import okio.Deflater
import okio.Inflater
import okio.buffer
import okio.deflate
import okio.inflate
import okio.use

/** zlib streams (header, deflate, Adler-32) as PNG text chunks hold them, on every platform, through Okio. */
internal object Zlib {
    fun inflate(data: ByteArray): ByteArray =
        Buffer().write(data).inflate(Inflater()).buffer().use { it.readByteArray() }

    fun deflate(data: ByteArray): ByteArray {
        val out = Buffer()
        out.deflate(Deflater()).buffer().use { it.write(data) }
        return out.readByteArray()
    }
}
