/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.github.danyk20.mediasync.core.model.DeviceId
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/**
 * Signs requests to the rendezvous Worker (docs/adr/0009-rendezvous-device-authentication.md, PLAN §8.4).
 *
 * Every request carries the phone's certificate and a signature by its identity key over the request, so the Worker
 * knows which device asks without accounts and without any secret in the app. The key stays in the keystore:
 * [sign] is the keystore's `SHA256withECDSA`, which already produces the DER signature the Worker expects.
 *
 * @param certificate the device's certificate, DER; its SHA-256 is the device id paired phones pin.
 * @param sign signs the canonical string's UTF-8 bytes.
 * @param clock the time in milliseconds; [clockOffsetSeconds] corrects it after the Worker reported a skew.
 */
class RendezvousSigner(
    private val certificate: ByteArray,
    private val sign: (ByteArray) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Added to this phone's clock once the Worker said it's off (`X-MediaSync-Server-Time`). */
    @Volatile
    var clockOffsetSeconds: Long = 0

    val deviceId: DeviceId = deviceIdOf(certificate)

    /** The four authentication headers for a request to [url] with [body] (empty when there's none). */
    fun headers(method: String, url: URI, body: ByteArray = ByteArray(0)): Map<String, String> {
        val timestamp = (clock() / MILLIS_PER_SECOND + clockOffsetSeconds).toString()
        val nonce = base64Url(ByteArray(NONCE_BYTES).also(random::nextBytes))
        val signature = sign(canonicalString(method, url, timestamp, nonce, body).encodeToByteArray())
        return linkedMapOf(
            CERTIFICATE to base64Url(certificate),
            TIMESTAMP to timestamp,
            NONCE to nonce,
            SIGNATURE to base64Url(signature),
        )
    }

    /** After an `auth_clock_skew` refusal: the Worker's time, so the retry is signed with a corrected timestamp. */
    fun adjustClock(serverTimeSeconds: Long) {
        clockOffsetSeconds = serverTimeSeconds - clock() / MILLIS_PER_SECOND
    }

    companion object {
        const val CERTIFICATE = "X-MediaSync-Certificate"
        const val TIMESTAMP = "X-MediaSync-Timestamp"
        const val NONCE = "X-MediaSync-Nonce"
        const val SIGNATURE = "X-MediaSync-Signature"

        /** In an `auth_clock_skew` refusal: the Worker's time, Unix seconds. */
        const val SERVER_TIME = "X-MediaSync-Server-Time"

        private const val NONCE_BYTES = 16
        private const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * What a request's signature covers (ADR 0009 §1): eight lines, joined by `\n`. The host is lower case, with its port
 * only when it isn't the scheme's default; the path and query exactly as sent.
 */
internal fun canonicalString(method: String, url: URI, timestamp: String, nonce: String, body: ByteArray): String {
    val host = url.host.lowercase()
    val defaultPort = when (url.scheme.lowercase()) {
        "https", "wss" -> HTTPS_PORT
        "http", "ws" -> HTTP_PORT
        else -> -1
    }
    val authority = if (url.port == -1 || url.port == defaultPort) host else "$host:${url.port}"
    return listOf(
        AUTH_VERSION,
        method.uppercase(),
        authority,
        url.rawPath.ifEmpty { "/" },
        url.rawQuery.orEmpty(),
        timestamp,
        nonce,
        body.toByteString().sha256().hex(),
    ).joinToString("\n")
}

/** A device's id: the SHA-256 of its DER certificate, as paired phones pin it. */
fun deviceIdOf(certificate: ByteArray): DeviceId = DeviceId.of(certificate.toByteString().sha256().toByteArray())

/**
 * A pair's mailbox at the Worker (ADR 0009 §2): derived from the two device ids, so it needs nothing new in the QR
 * code. Not a secret: only the two member devices can use it.
 */
fun pairId(consumer: DeviceId, producer: DeviceId): String = Buffer()
    .writeUtf8(PAIR_LABEL)
    .write(consumer.hex.decodeHex())
    .write(producer.hex.decodeHex())
    .sha256()
    .hex()

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private const val AUTH_VERSION = "MEDIASYNC-AUTH-V1"
private const val PAIR_LABEL = "mediasync-pair-v1"
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80
