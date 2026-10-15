/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.github.danyk20.mediasync.core.model.DeviceId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.jupiter.api.Test

/** Requests to the rendezvous Worker are signed exactly as ADR 0009 says, and as the Worker checks them. */
class RendezvousAuthTest {

    @Test
    fun theGoldenRequestHasTheSameCanonicalStringDeviceIdAndAValidSignature() {
        val certificate = Golden.CERTIFICATE.fromBase64Url()

        val canonical = canonicalString(Golden.METHOD, URI(Golden.URL), Golden.TIMESTAMP, Golden.NONCE, Golden.BODY)

        canonical shouldBe Golden.CANONICAL
        deviceIdOf(certificate).hex shouldBe Golden.DEVICE_ID
        val key = CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream()).publicKey
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(canonical.encodeToByteArray())
            verify(Golden.SIGNATURE.fromBase64Url())
        } shouldBe true
    }

    @Test
    fun pairIdsAreDerivedFromBothDevices() {
        pairId(DeviceId("1".repeat(64)), DeviceId("2".repeat(64))) shouldBe
            "f684b5739a33d77bf1ab7936fc3e76551e03e0044387ebb9a66fc18da5fac8df"
        pairId(DeviceId(Golden.DEVICE_ID), DeviceId("2".repeat(64))) shouldBe Golden.PAIR_ID
        pairId(DeviceId("2".repeat(64)), DeviceId("1".repeat(64))) shouldNotBe pairId(
            DeviceId("1".repeat(64)),
            DeviceId("2".repeat(64)),
        ) // The consumer comes first.
    }

    @Test
    fun theHostIsLowerCaseWithItsPortOnlyWhenNotTheDefault() {
        fun line(url: String) = canonicalString("get", URI(url), "1", "n", ByteArray(0)).lines()

        line("https://Rendezvous.Example.com/v1/x?b=1&a=2") shouldBe listOf(
            "MEDIASYNC-AUTH-V1",
            "GET",
            "rendezvous.example.com",
            "/v1/x",
            "b=1&a=2",
            "1",
            "n",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", // SHA-256 of nothing.
        )
        line("https://example.com:443/v1")[2] shouldBe "example.com"
        line("https://example.com:8443/v1")[2] shouldBe "example.com:8443"
        line("http://localhost:8787/v1")[2] shouldBe "localhost:8787"
    }

    @Test
    fun aSignedRequestVerifiesWithTheDevicesKeyAndCarriesAFreshNonce() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val signer = RendezvousSigner(
            certificate = byteArrayOf(1, 2, 3), // Only hashed and sent here; the Worker parses it.
            sign = { data ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keys.private)
                    update(data)
                    sign()
                }
            },
            clock = { 1_792_000_000_123 },
        )
        val url = URI("https://rendezvous.example.com/v1/pairs/abc/relay-usage")
        val body = """{"relayedBytes":1}""".encodeToByteArray()

        val headers = signer.headers("POST", url, body)

        headers.keys shouldBe
            setOf("X-MediaSync-Certificate", "X-MediaSync-Timestamp", "X-MediaSync-Nonce", "X-MediaSync-Signature")
        headers.getValue("X-MediaSync-Timestamp") shouldBe "1792000000"
        headers.getValue("X-MediaSync-Nonce") shouldMatch Regex("[A-Za-z0-9_-]{22}")
        headers.getValue("X-MediaSync-Certificate") shouldBe "AQID"
        val canonical = canonicalString(
            "POST",
            url,
            headers.getValue("X-MediaSync-Timestamp"),
            headers.getValue("X-MediaSync-Nonce"),
            body,
        )
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public)
            update(canonical.encodeToByteArray())
            verify(headers.getValue("X-MediaSync-Signature").fromBase64Url())
        } shouldBe true
        val again = signer.headers("POST", url, body)
        again.getValue("X-MediaSync-Nonce") shouldNotBe headers.getValue("X-MediaSync-Nonce")
    }

    @Test
    fun aClockTheWorkerFoundOffIsCorrectedForTheRetry() {
        val signer = RendezvousSigner(byteArrayOf(1), { it }, clock = { 1_000_000 })

        signer.adjustClock(serverTimeSeconds = 1_400)

        signer.headers("GET", URI("https://example.com/v1/x")).getValue("X-MediaSync-Timestamp") shouldBe "1400"
    }

    private fun String.fromBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

    /** infra/rendezvous/test/fixtures/golden-request.json: signed by OpenSSL with an OpenSSL-made P-256 certificate. */
    private object Golden {
        const val METHOD = "POST"
        const val URL = "https://rendezvous.example.com/v1/pairs/" +
            "0bad1f82c8a4124c9c5e77c7cdf65a8e86b1eed827c484d0385cd6f564c81333/relay-usage"
        val BODY = """{"session":"AAAAAAAAAAAAAAAAAAAAAA","relayedBytes":1048576}""".encodeToByteArray()
        const val DEVICE_ID = "a89e12b464b1f1cc17ef75bc31fe204efc1e0f0c0d2ce58aac4f11f85dfabc67"
        const val PAIR_ID = "0bad1f82c8a4124c9c5e77c7cdf65a8e86b1eed827c484d0385cd6f564c81333"
        const val TIMESTAMP = "1792000000"
        const val NONCE = "AAECAwQFBgcICQoLDA0ODw"
        const val CANONICAL = "MEDIASYNC-AUTH-V1\nPOST\nrendezvous.example.com\n" +
            "/v1/pairs/0bad1f82c8a4124c9c5e77c7cdf65a8e86b1eed827c484d0385cd6f564c81333/relay-usage\n\n" +
            "1792000000\nAAECAwQFBgcICQoLDA0ODw\ne75c9e2aa3fbd15b8283a9865e038468e3e0b78cb1dc361a16e1472cdfd87b65"
        const val CERTIFICATE =
            "MIIBazCCARKgAwIBAgIUQzzsmJDI-WVNCAvtGopOZhqLpMQwCgYIKoZIzj0EAwIwGzEZMBcGA1UEAwwQTWVkaWFTeW" +
                "5jIGRldmljZTAgFw0yNjEwMTQyMjI2MjlaGA8yMDU2MTAwNjIyMjYyOVowGzEZMBcGA1UEAwwQTWVkaWFTeW5jIGRl" +
                "dmljZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABL57WcoKnToTiX-_txNlaIRBMhwSjnh5Li98qBKiRzzUdJdC7c" +
                "iuQzNzBGNWLP5ZdgMjLNDU5G-dc7qdAezvDKWjMjAwMB0GA1UdDgQWBBQRWrpZt4K-A4O-6nWxhkzym1VK_zAPBgNV" +
                "HRMBAf8EBTADAQH_MAoGCCqGSM49BAMCA0cAMEQCIBRb2z3oXAigH8UsSQW3k4IqwedSe8w46Qagz-_oFT70AiAz_t" +
                "txvUiu5omVKecgihn5N8EmFlr44cobHyQcGnK3oQ"
        const val SIGNATURE =
            "MEQCIH4p2xYvr2ORPnN1N9PMmr7WJ3svJRed-CcLZ6I5QGJxAiBcboaDU6cvKdq68j1kX8BQym1mVLzcPZymBjBLqC" +
                "Fdhw"
    }
}
