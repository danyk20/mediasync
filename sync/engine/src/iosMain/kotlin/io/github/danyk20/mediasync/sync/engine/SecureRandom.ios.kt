/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

actual fun secureRandom(): Random = IosSecureRandom

/** The Security framework's random bytes (SecRandomCopyBytes). */
@OptIn(ExperimentalForeignApi::class)
private object IosSecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        val bytes = ByteArray(Int.SIZE_BYTES)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0)) }
        check(status == 0) { "SecRandomCopyBytes failed with $status" }
        var bits = 0
        for (byte in bytes) bits = (bits shl Byte.SIZE_BITS) or (byte.toInt() and BYTE_MASK)
        return bits ushr (Int.SIZE_BITS - bitCount)
    }

    private const val BYTE_MASK = 0xFF
}
