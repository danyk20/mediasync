/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

/** The subject of every device certificate: the same name Android Keystore gives its identity. */
internal const val DEVICE_COMMON_NAME = "MediaSync device"

private const val DAY_MILLIS = 86_400_000L
private const val VALIDITY_YEARS = 30
private const val DAYS_PER_YEAR = 365L

/** Uncompressed EC point: `04 || X || Y` for P-256. */
private const val X963_UNCOMPRESSED_P256_SIZE = 65
private const val X963_UNCOMPRESSED_TAG = 0x04.toByte()
private const val MAX_SERIAL_BYTES = 20

/** ecdsa-with-SHA256 (1.2.840.10045.4.3.2), no parameters. */
private val SIGNATURE_ALGORITHM = DerWriter.sequence(
    DerWriter.oid(hex("2A8648CE3D040302")),
)

/** id-ecPublicKey (1.2.840.10045.2.1) and prime256v1 (1.2.840.10045.3.1.7). */
private val EC_PUBLIC_KEY_ALGORITHM = DerWriter.sequence(
    DerWriter.oid(hex("2A8648CE3D0201")),
    DerWriter.oid(hex("2A8648CE3D030107")),
)

/** commonName (2.5.4.3). */
private val COMMON_NAME_OID = DerWriter.oid(hex("550403"))

/**
 * Builds a self-signed X.509 v3 certificate for an EC P-256 key, with the layout of the certificate Android
 * Keystore makes on each device: no extensions, subject and issuer `CN=MediaSync device`, valid from a day ago for
 * 30 years, ecdsa-with-SHA256. The peers pin the SHA-256 of these bytes, so the layout must not change.
 *
 * @param publicKeyX963 the 65-byte uncompressed public point (`SecKeyCopyExternalRepresentation` gives this form).
 * @param serial 1 to 20 random bytes. The first is forced into 0x01..0x7F so the integer is positive.
 * @param sign signs the DER-encoded TBSCertificate with ecdsa-with-SHA256 and returns the X9.62 DER signature
 *   (`ECDSA-Sig-Value`), which is what the certificate's signature field holds.
 * @return the DER-encoded certificate
 */
internal fun buildSelfSignedP256Certificate(
    publicKeyX963: ByteArray,
    nowMillis: Long,
    serial: ByteArray,
    sign: (tbsCertificate: ByteArray) -> ByteArray,
): ByteArray {
    require(publicKeyX963.size == X963_UNCOMPRESSED_P256_SIZE && publicKeyX963[0] == X963_UNCOMPRESSED_TAG) {
        "Expected a 65-byte uncompressed P-256 point, got ${publicKeyX963.size} bytes"
    }
    require(serial.size in 1..MAX_SERIAL_BYTES) { "Serial must be 1 to $MAX_SERIAL_BYTES bytes" }

    val name = DerWriter.sequence(
        DerWriter.set(DerWriter.sequence(COMMON_NAME_OID, DerWriter.utf8String(DEVICE_COMMON_NAME))),
    )
    val validity = DerWriter.sequence(
        DerWriter.time(nowMillis - DAY_MILLIS),
        DerWriter.time(nowMillis + VALIDITY_YEARS * DAYS_PER_YEAR * DAY_MILLIS),
    )
    val subjectPublicKeyInfo = DerWriter.sequence(EC_PUBLIC_KEY_ALGORITHM, DerWriter.bitString(publicKeyX963))
    val tbsCertificate = DerWriter.sequence(
        DerWriter.explicitContext0(DerWriter.integer(byteArrayOf(2))),
        DerWriter.integer(positiveSerial(serial)),
        SIGNATURE_ALGORITHM,
        name,
        validity,
        name,
        subjectPublicKeyInfo,
    )
    val signature = sign(tbsCertificate)
    require(signature.isNotEmpty() && signature[0] == SEQUENCE_TAG) { "The signature must be X9.62 DER" }
    return DerWriter.sequence(tbsCertificate, SIGNATURE_ALGORITHM, DerWriter.bitString(signature))
}

private const val SEQUENCE_TAG = 0x30.toByte()

/** The serial as a positive INTEGER: the top bit of the first byte is cleared and a zero first byte becomes 1. */
private fun positiveSerial(serial: ByteArray): ByteArray {
    val first = (serial[0].toInt() and 0x7F).let { if (it == 0) 1 else it }
    return byteArrayOf(first.toByte()) + serial.copyOfRange(1, serial.size)
}

/** Parses an even-length hex string into bytes; used for the fixed OIDs above. */
private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
    text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
}
