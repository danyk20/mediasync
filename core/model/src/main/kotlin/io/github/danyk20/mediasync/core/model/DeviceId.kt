/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/**
 * A device's identity: the SHA-256 of its identity certificate, as 64 lowercase hex characters.
 *
 * It's also the certificate pin. A connection is only trusted when the peer presents the certificate
 * with exactly this hash.
 */
@JvmInline
value class DeviceId(val hex: String) {
    init {
        require(hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "DeviceId must be $HEX_LENGTH lowercase hex characters"
        }
    }

    /** Short form for logs and UI diagnostics; never use it to identify a device. */
    val short: String get() = hex.take(SHORT_LENGTH)

    override fun toString(): String = "DeviceId($short…)"

    companion object {
        private const val HEX_LENGTH = 64
        private const val SHORT_LENGTH = 8

        fun of(sha256: ByteArray): DeviceId {
            require(sha256.size == HEX_LENGTH / 2) { "Expected a 32-byte SHA-256, got ${sha256.size} bytes" }
            return DeviceId(sha256.joinToString("") { "%02x".format(it) })
        }
    }
}
