/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the phones' signaling messages (WebRTC offers, answers and candidates) with their pair key, so the Worker
 * relays them unread (ADR 0009 §4, PLAN §8.4).
 *
 * AES-256-GCM with a random 96-bit nonce; the key is HKDF-SHA256 of the pair's 32-byte secret, which the consumer gave
 * the producer inside their pinned TLS session. The pair id is authenticated with every message, so one can't be
 * replayed into another pair's mailbox. A sealed message is `version (1) | nonce (12) | ciphertext and tag`.
 */
class SignalingCipher(pairSecret: ByteArray, pairId: String, private val random: SecureRandom = SecureRandom()) {
    private val key =
        SecretKeySpec(hkdfSha256(pairSecret, SALT.encodeToByteArray(), INFO.encodeToByteArray(), KEY_BYTES), "AES")
    private val associatedData = pairId.encodeToByteArray()

    init {
        require(pairSecret.size == SECRET_BYTES) { "A pair secret has $SECRET_BYTES bytes, not ${pairSecret.size}" }
    }

    fun seal(message: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(message)
        return byteArrayOf(VERSION) + nonce + sealed
    }

    /** @throws IOException if [sealed] wasn't sealed with this pair's key for this pair, or was changed on the way. */
    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + NONCE_BYTES + TAG_BYTES || sealed[0] != VERSION) throw IOException("Not a sealed message")
        val nonce = sealed.copyOfRange(1, 1 + NONCE_BYTES)
        return try {
            cipher(Cipher.DECRYPT_MODE, nonce).doFinal(sealed, 1 + NONCE_BYTES, sealed.size - 1 - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw IOException("A signaling message failed its check", e)
        }
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(mode, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, nonce))
        updateAAD(associatedData)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val SECRET_BYTES = 32
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val SALT = "mediasync-signaling-salt-v1"
        const val INFO = "mediasync-signaling-v1"
    }
}

/** HKDF with HMAC-SHA256 (RFC 5869): [length] bytes of key from [ikm]. */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val extract = Mac.getInstance(HMAC).apply {
        init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, HMAC))
    }
    val prk = extract.doFinal(ikm)
    val expand = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
    val out = ByteArray(length)
    var block = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
        expand.update(block)
        expand.update(info)
        expand.update(counter.toByte())
        block = expand.doFinal()
        val take = minOf(block.size, length - written)
        System.arraycopy(block, 0, out, written, take)
        written += take
        counter++
    }
    return out
}

private const val HMAC = "HmacSHA256"
private const val HASH_BYTES = 32
