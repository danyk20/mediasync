/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import okio.ByteString
import okio.ByteString.Companion.toByteString
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

internal actual fun newSha256(): Sha256 = IosSha256()

/**
 * CommonCrypto's SHA-256, which uses the CPU's SHA-2 instructions. The context is a plain byte array: it has no
 * pointers of its own, so pinning it for each call is enough, and an abandoned instance leaks nothing.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSha256 : Sha256 {
    private val context = ByteArray(sizeOf<CC_SHA256_CTX>().toInt())

    init {
        context.usePinned { CC_SHA256_Init(it.addressOf(0).reinterpret()) }
    }

    private var finished = false

    override fun update(bytes: ByteArray, offset: Int, byteCount: Int) {
        require(offset >= 0 && byteCount >= 0 && offset <= bytes.size - byteCount)
        check(!finished) { "update after digest" }
        if (byteCount == 0) return
        context.usePinned { ctx ->
            bytes.usePinned { data ->
                CC_SHA256_Update(ctx.addressOf(0).reinterpret(), data.addressOf(offset), byteCount.convert())
            }
        }
    }

    override fun digest(): ByteString {
        check(!finished) { "digest already taken" }
        finished = true
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        context.usePinned { ctx ->
            out.usePinned { md -> CC_SHA256_Final(md.addressOf(0).reinterpret(), ctx.addressOf(0).reinterpret()) }
        }
        return out.toByteString()
    }
}
