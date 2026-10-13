/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** A self-signed EC P-256 identity, like the one Android Keystore generates on each device. */
fun testIdentity(name: String): DeviceIdentity {
    val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    val subject = X500Name("CN=$name")
    val now = System.currentTimeMillis()
    val certificate = JcaX509v3CertificateBuilder(
        subject,
        BigInteger.valueOf(now),
        Date(now - TimeUnit.DAYS.toMillis(1)),
        Date(now + TimeUnit.DAYS.toMillis(365)),
        subject,
        keyPair.public,
    ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))
    return DeviceIdentity(JcaX509CertificateConverter().getCertificate(certificate), keyPair.private)
}
