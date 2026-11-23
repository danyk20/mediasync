/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import okio.ByteString

/** Incremental SHA-256. Not thread-safe: one instance per file. */
internal interface Sha256 {
    /** Adds [byteCount] bytes of [bytes], starting at [offset]. */
    fun update(bytes: ByteArray, offset: Int = 0, byteCount: Int = bytes.size - offset)

    /** Finishes and returns the 32-byte digest. The instance is not used again afterwards. */
    fun digest(): ByteString
}

/** A new SHA-256 computation: the platform's own implementation (MessageDigest on the JVM, CommonCrypto on iOS). */
internal expect fun newSha256(): Sha256
