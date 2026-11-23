/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi

class IdentityTest {
    @Test
    fun identityIsMadeFromAKeyAndACertificateWithoutTheKeychain() {
        val identity = AppleIdentityFactory.create()

        // The DER is a SEQUENCE, and the id is the SHA-256 of exactly those bytes (the pin the peers check).
        assertTrue(identity.certificateDer[0] == 0x30.toByte())
        assertEquals(DeviceId.of(identity.certificateDer.sha256()), identity.id)
        assertEquals(DeviceId.of(identity.certificateDer.sha256()).hex.length, 64)
        recordPass("identityIsMadeFromAKeyAndACertificateWithoutTheKeychain")
    }

    @Test
    fun eachIdentityHasItsOwnId() {
        val first = AppleIdentityFactory.create()
        val second = AppleIdentityFactory.create()

        assertTrue(first.id != second.id)
        recordPass("eachIdentityHasItsOwnId")
    }

    @Test
    fun theCertificateSignatureVerifiesWithItsOwnPublicKeyOnIos() {
        val der = AppleIdentityFactory.create().certificateDer

        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue BIT STRING }
        val certificate = readTlv(der, 0)
        val tbs = readTlv(der, certificate.contentStart)
        val signatureAlgorithm = readTlv(der, tbs.end)
        val signatureValue = readTlv(der, signatureAlgorithm.end)
        assertEquals(BIT_STRING_TAG, signatureValue.tag)

        // The signed bytes are the whole TBSCertificate TLV. The BIT STRING starts with a count of unused bits (0).
        val tbsBytes = der.copyOfRange(tbs.start, tbs.end)
        val signature = der.copyOfRange(signatureValue.contentStart + 1, signatureValue.end)
        assertTrue(verifiesWithCertificate(der, tbsBytes, signature), "The certificate's own signature must verify")

        val tampered = tbsBytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertFalse(verifiesWithCertificate(der, tampered, signature), "A changed TBSCertificate must not verify")
        recordPass("theCertificateSignatureVerifiesWithItsOwnPublicKeyOnIos")
    }

    /** A DER element: its tag, its start offset, where its content starts, and where it ends. */
    private class Tlv(val tag: Int, val start: Int, val contentStart: Int, val end: Int)

    /** Reads the element that starts at [offset]. Handles the short and the long length forms. */
    private fun readTlv(bytes: ByteArray, offset: Int): Tlv {
        val tag = bytes[offset].toInt() and BYTE_MASK
        var position = offset + 1
        val firstLength = bytes[position++].toInt() and BYTE_MASK
        val length = if (firstLength < LONG_FORM_FLAG) {
            firstLength
        } else {
            var value = 0
            repeat(firstLength and LONG_FORM_COUNT_MASK) {
                value = (value shl BYTE_BITS) or (bytes[position++].toInt() and BYTE_MASK)
            }
            value
        }
        return Tlv(tag, offset, position, position + length)
    }

    private companion object {
        const val BIT_STRING_TAG = 0x03
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
        const val LONG_FORM_FLAG = 0x80
        const val LONG_FORM_COUNT_MASK = 0x7F
    }
}
