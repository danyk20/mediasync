/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import okio.ByteString
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
        append("&t=").append(BASE64.encodeToString(token.toByteArray()))
        append("&a=").append(addresses.joinToString(",") { encode(it) })
        append("&n=").append(encode(consumerName))
    }

    companion object {
        const val TOKEN_BYTES = 16
        private const val VERSION = "1"
        private const val SCHEME_PREFIX = "mediasync://pair?"
        private const val MAX_NAME_LENGTH = 64
        private val BASE64 = Base64.getUrlEncoder().withoutPadding()

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
                    token = Base64.getUrlDecoder().decode(params.getValue("t")).toByteString(),
                    addresses = params.getValue("a").split(",").map(::decode).filter { it.isNotBlank() },
                    consumerName = decode(params.getValue("n")),
                )
            }.getOrNull()
        }

        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

        private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8)
    }
}
