/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.core.model.DeviceId
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * This device's TLS identity: a self-signed certificate and its private key.
 *
 * On Android the key lives in the hardware-backed keystore and never leaves it. Only a handle is held
 * here, and signing happens inside the keystore during the TLS handshake.
 */
class DeviceIdentity(val certificate: X509Certificate, val privateKey: PrivateKey) {
    val id: DeviceId = certificate.deviceId()
}

/** The certificate pin: SHA-256 over the DER encoding. */
fun X509Certificate.deviceId(): DeviceId = DeviceId.of(MessageDigest.getInstance("SHA-256").digest(encoded))
