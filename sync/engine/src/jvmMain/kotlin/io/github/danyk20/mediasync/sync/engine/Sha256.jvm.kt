/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import java.security.MessageDigest
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal actual fun newSha256(): Sha256 = JvmSha256(MessageDigest.getInstance("SHA-256"))

private class JvmSha256(private val md: MessageDigest) : Sha256 {
    override fun update(bytes: ByteArray, offset: Int, byteCount: Int) {
        md.update(bytes, offset, byteCount)
    }

    override fun digest(): ByteString = md.digest().toByteString()
}
