/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * What the consumer's pairing QR code contains (docs/PLAN.md §11):
 * `mediasync://pair?v=1&id=<device id>&t=<one-time token>&a=<host:port,…>&n=<name>`.
 *
 * [consumer] pins the consumer's certificate, so the producer can't be tricked into pairing with another
 * device. The [token] proves to the consumer that the producer really scanned this code.
 */
data class PairingLink(
    val consumer: DeviceId,
    val token: ByteString,
    /** Local addresses to try, e.g. `192.168.1.20:47420` or `[fd00::1]:47420`. */
    val addresses: List<String>,
    val consumerName: String,
) {
    init {
        require(token.size == TOKEN_BYTES) { "Token must be $TOKEN_BYTES bytes" }
        require(addresses.isNotEmpty()) { "At least one address is required" }
        require(consumerName.length <= MAX_NAME_LENGTH) { "Name longer than $MAX_NAME_LENGTH characters" }
    }

    override fun toString(): String = buildString {
        append(SCHEME_PREFIX)
        append("v=").append(VERSION)
        append("&id=").append(consumer.hex)
        append("&t=").append(token.base64Url().trimEnd('='))
        append("&a=").append(addresses.joinToString(",") { PercentCodec.encode(it) })
        append("&n=").append(PercentCodec.encode(consumerName))
    }

    companion object {
        const val TOKEN_BYTES = 16
        private const val VERSION = "1"
        private const val SCHEME_PREFIX = "mediasync://pair?"
        private const val MAX_NAME_LENGTH = 64
        private const val TOKEN_CHARACTERS = 22
        private val TOKEN_TEXT = Regex("[A-Za-z0-9_-]{$TOKEN_CHARACTERS}(==)?")

        /** Parses a scanned or pasted link; `null` if it isn't a valid MediaSync pairing link. */
        fun parse(text: String): PairingLink? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(SCHEME_PREFIX)) return null
            val params = trimmed.removePrefix(SCHEME_PREFIX).split("&").mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) null else pair.substring(0, separator) to pair.substring(separator + 1)
            }.toMap()
            if (params["v"] != VERSION) return null
            return runCatching {
                PairingLink(
                    consumer = DeviceId(params.getValue("id")),
                    token = decodeToken(params.getValue("t")),
                    addresses = params.getValue("a").split(",").map(PercentCodec::decode).filter { it.isNotBlank() },
                    consumerName = PercentCodec.decode(params.getValue("n")),
                )
            }.getOrNull()
        }

        /**
         * The token as [PairingLink] writes it: URL-safe base64 without padding. The strict pattern first: the decoder
         * also takes `+`, `/`, whitespace and sloppy padding, which the link never contains.
         */
        private fun decodeToken(text: String): ByteString {
            require(TOKEN_TEXT.matches(text)) { "Not a pairing token" }
            return text.decodeBase64()?.takeIf { it.size == TOKEN_BYTES }
                ?: throw IllegalArgumentException("Not a pairing token")
        }
    }
}
