/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.EOFException

/** A saved identity is unreadable: its bytes are cut short, changed, or not the shape an identity has. */
open class CorruptIdentityException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/**
 * A saved identity whose private key does not belong to its certificate, or whose private key Security rejects. It is
 * regenerated like any other corrupt identity.
 */
class IdentityMismatchException(reason: String) : CorruptIdentityException(reason)

/**
 * This device's identity as bytes, the form every [AppleIdentityStore] keeps.
 *
 * @property privateKey the P-256 private key in ANSI X9.63 form (`04 || X || Y || K`, 97 bytes), which is what
 *   `SecKeyCopyExternalRepresentation` gives for a private key and what `SecKeyCreateWithData` takes back.
 * @property certificateDer the certificate; its SHA-256 is the device id.
 */
internal class AppleIdentityMaterial(val privateKey: ByteArray, val certificateDer: ByteArray) {
    init {
        if (privateKey.size != PRIVATE_KEY_BYTES || privateKey[0] != X963_UNCOMPRESSED_TAG) {
            throw CorruptIdentityException("the private key is not a P-256 key of $PRIVATE_KEY_BYTES bytes")
        }
        if (certificateDer.isEmpty() || certificateDer.size > MAX_CERTIFICATE_BYTES ||
            certificateDer[0] != DER_SEQUENCE
        ) {
            throw CorruptIdentityException("the certificate is not a DER certificate")
        }
    }

    private companion object {
        const val PRIVATE_KEY_BYTES = 97
        const val MAX_CERTIFICATE_BYTES = 4096
        const val X963_UNCOMPRESSED_TAG = 0x04.toByte()
        const val DER_SEQUENCE = 0x30.toByte()
    }
}

/**
 * The file layout of [FileIdentityStore]: the magic `MSID`, a version byte, the private key and the certificate
 * each with a 2-byte big-endian length, and the SHA-256 of everything before it. The checksum catches any change to
 * the file, including one that still parses, so a damaged file regenerates instead of silently changing the device id.
 */
internal object IdentityFile {
    private val magic = "MSID".encodeToByteArray()
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32

    fun encode(material: AppleIdentityMaterial): ByteArray {
        val body = Buffer()
        body.write(magic)
        body.writeByte(VERSION)
        body.writeShort(material.privateKey.size)
        body.write(material.privateKey)
        body.writeShort(material.certificateDer.size)
        body.write(material.certificateDer)
        val payload = body.readByteArray()
        return payload + checksum(payload)
    }

    /** The material in [bytes]; [CorruptIdentityException] for anything that is not exactly one encoded identity. */
    fun decode(bytes: ByteArray): AppleIdentityMaterial {
        val payload = verifiedPayload(bytes)
        try {
            val source = Buffer().write(payload)
            checkHeader(source)
            val privateKey = source.readByteArray(source.readShort().toLong() and LENGTH_MASK)
            val certificate = source.readByteArray(source.readShort().toLong() and LENGTH_MASK)
            if (!source.exhausted()) throw CorruptIdentityException("the file has bytes after the certificate")
            return AppleIdentityMaterial(privateKey, certificate)
        } catch (error: EOFException) {
            throw CorruptIdentityException("the file ends inside a field", error)
        }
    }

    /** The bytes before the checksum, once the checksum has been checked against them. */
    private fun verifiedPayload(bytes: ByteArray): ByteArray {
        if (bytes.size <= magic.size + 1 + CHECKSUM_BYTES) throw CorruptIdentityException("the file is truncated")
        val payload = bytes.copyOf(bytes.size - CHECKSUM_BYTES)
        if (!checksum(payload).contentEquals(bytes.copyOfRange(payload.size, bytes.size))) {
            throw CorruptIdentityException("the checksum does not match")
        }
        return payload
    }

    /** Reads the magic and the version; anything else is not an identity file of this version. */
    private fun checkHeader(source: Buffer) {
        if (!source.readByteArray(magic.size.toLong()).contentEquals(magic)) {
            throw CorruptIdentityException("the file is not an identity file")
        }
        val version = source.readByte().toInt()
        if (version != VERSION) throw CorruptIdentityException("the file version $version is not supported")
    }

    private fun checksum(payload: ByteArray): ByteArray = payload.toByteString().sha256().toByteArray()

    private const val LENGTH_MASK = 0xFFFFL
}

/** Whether [needle] occurs in this array, byte for byte. */
internal fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
