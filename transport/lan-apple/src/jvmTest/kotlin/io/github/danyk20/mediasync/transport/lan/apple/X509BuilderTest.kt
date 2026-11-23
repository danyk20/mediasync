/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class X509BuilderTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private val now: Long = Instant.parse("2026-11-23T10:15:30Z").toEpochMilli()

    private fun x963(publicKey: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + publicKey.w.affineX.to32() + publicKey.w.affineY.to32()

    private fun BigInteger.to32(): ByteArray {
        val raw = toByteArray()
        return if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
    }

    /** Signs like iOS does: SHA-256 of the TBS, then the X9.62 DER signature. */
    private fun jcaSign(tbs: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        return signer.sign()
    }

    private fun build(nowMillis: Long, serial: ByteArray = randomSerial()): ByteArray =
        buildSelfSignedP256Certificate(x963(keyPair.public as ECPublicKey), nowMillis, serial, ::jcaSign)

    private fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun randomSerial() = ByteArray(8).also { SecureRandom().nextBytes(it) }

    @Test
    fun certificateParsesWithTheJdkAndHasTheDeviceFields() {
        val der = build(now)
        val cert = parse(der)

        assertEquals(3, cert.version)
        assertContentEquals(der, cert.encoded)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(X500Principal("CN=$DEVICE_COMMON_NAME"), cert.subjectX500Principal)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertContentEquals(keyPair.public.encoded, cert.publicKey.encoded)
        assertTrue(cert.nonCriticalExtensionOIDs.isNullOrEmpty())
        assertTrue(cert.criticalExtensionOIDs.isNullOrEmpty())
        cert.verify(keyPair.public)
    }

    @Test
    fun validityIsOneDayBackAndThirtyYearsForward() {
        val cert = parse(build(now))

        assertEquals(now - 86_400_000L, cert.notBefore.time)
        assertEquals(Instant.ofEpochMilli(now).plus(30L * 365, ChronoUnit.DAYS).toEpochMilli(), cert.notAfter.time)
    }

    @Test
    fun notAfterPastYear2050IsEncodedAsGeneralizedTime() {
        // Now plus 30 years is 2056: the time must be GeneralizedTime (tag 0x18, length 15, "2056...Z").
        val der = build(now)
        val cert = parse(der)

        assertEquals(2056, cert.notAfter.toInstant().atZone(ZoneOffset.UTC).year)
        val marker = byteArrayOf(0x18, 0x0F) + "2056".encodeToByteArray()
        assertTrue(String(der, Charsets.ISO_8859_1).contains(String(marker, Charsets.ISO_8859_1)))
    }

    @Test
    fun serialIsPositiveWhateverTheRandomBytes() {
        // High bit set: the first byte drops to 0x7F. Zero first byte: it becomes 0x01.
        val highBit = parse(build(now, serial = byteArrayOf(0xFF.toByte(), 0x01)))
        val zeroFirst = parse(build(now, serial = byteArrayOf(0x00, 0x7F)))

        assertEquals(BigInteger.valueOf(0x7F01), highBit.serialNumber)
        assertEquals(BigInteger.valueOf(0x017F), zeroFirst.serialNumber)
    }

    @Test
    fun rejectsAPublicKeyThatIsNotAnUncompressedP256Point() {
        assertFailsWith<IllegalArgumentException> {
            buildSelfSignedP256Certificate(ByteArray(33), now, randomSerial(), ::jcaSign)
        }
    }

    @Test
    fun rejectsASignatureThatIsNotDer() {
        assertFailsWith<IllegalArgumentException> {
            buildSelfSignedP256Certificate(x963(keyPair.public as ECPublicKey), now, randomSerial()) {
                byteArrayOf(0x00)
            }
        }
    }

    @Test
    fun timesMatchJavaTimeAcrossTheUtcTimeAndGeneralizedTimeBoundary() {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
        val instants = listOf(
            Instant.parse("1970-01-01T00:00:00Z"),
            Instant.parse("2000-02-29T23:59:59Z"),
            Instant.parse("2026-11-23T10:15:30Z"),
            Instant.parse("2049-12-31T23:59:59Z"),
            Instant.parse("2050-01-01T00:00:00Z"),
            Instant.parse("2400-02-29T12:00:00Z"),
        )
        for (instant in instants) {
            val der = DerWriter.time(instant.toEpochMilli())
            val utc = instant.atZone(ZoneOffset.UTC).year < 2050
            assertEquals(if (utc) 0x17 else 0x18, der[0].toInt() and 0xFF, "tag for $instant")
            val text = der.copyOfRange(2, der.size).decodeToString()
            val digits = formatter.format(instant)
            assertEquals((if (utc) digits.substring(2) else digits) + "Z", text, "content for $instant")
        }
    }

    @Test
    fun civilDateConversionMatchesJavaTime() {
        for (days in listOf(0L, 1L, 59L, 365L, 11_016L, 20_454L, 47_482L)) {
            val date = Instant.ofEpochSecond(days * 86_400).atZone(ZoneOffset.UTC).toLocalDate()
            assertEquals(
                Triple(date.year, date.monthValue, date.dayOfMonth),
                civilFromDays(days),
                "date for day $days",
            )
        }
    }

    @Test
    fun lengthsUseTheShortAndLongDerForms() {
        assertContentEquals(byteArrayOf(0x7F), DerWriter.length(127))
        assertContentEquals(byteArrayOf(0x81.toByte(), 0x80.toByte()), DerWriter.length(128))
        assertContentEquals(byteArrayOf(0x82.toByte(), 0x01, 0x2C), DerWriter.length(300))
    }
}
