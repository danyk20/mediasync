/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import java.nio.ByteBuffer
import java.security.MessageDigest
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Reads [byteCount] bytes starting at [offset]. Must return exactly that many bytes. */
fun interface ByteRangeReader {
    fun read(offset: Long, byteCount: Int): ByteArray
}

/**
 * Cheap content fingerprints, computed before anything is transferred.
 *
 * A fingerprint is the SHA-256 of the file size, the first [SAMPLE_BYTES] and the last [SAMPLE_BYTES]
 * (or of the whole file when it's no bigger than both samples together). Reading 512 KiB instead of the
 * whole file keeps indexing a large library fast. Changing the size, the headers (EXIF/XMP, video
 * metadata) or the end of the file changes the fingerprint, which covers edits in practice.
 *
 * Fingerprints decide *whether* to transfer (dedupe, resume, "already sent"). Every transfer is still
 * verified with a full SHA-256 of the bytes.
 */
object Fingerprints {
    const val SAMPLE_BYTES: Int = 256 * 1024
    private val DOMAIN = "mediasync-fingerprint-v1".encodeToByteArray()

    fun of(size: Long, reader: ByteRangeReader): ByteString {
        require(size >= 0) { "Size must not be negative" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(size).array())
        if (size <= 2L * SAMPLE_BYTES) {
            digest.update(reader.read(0, size.toInt()))
        } else {
            digest.update(reader.read(0, SAMPLE_BYTES))
            digest.update(reader.read(size - SAMPLE_BYTES, SAMPLE_BYTES))
        }
        return digest.digest().toByteString()
    }

    /** Convenience for in-memory content (tests, small files). */
    fun of(bytes: ByteArray): ByteString = of(bytes.size.toLong()) { offset, count ->
        bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
    }
}
