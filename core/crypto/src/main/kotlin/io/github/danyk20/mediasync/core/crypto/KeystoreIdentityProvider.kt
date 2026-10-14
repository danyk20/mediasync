/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.transport.lan.DeviceIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

/** This device's TLS identity. */
fun interface IdentityProvider {
    fun identity(): DeviceIdentity
}

/**
 * Keeps the device identity in the Android Keystore (docs/PLAN.md §11).
 *
 * The EC P-256 key is generated once, in StrongBox when the phone has one, otherwise in the TEE. It never
 * leaves secure hardware: TLS handshakes ask the keystore to sign. The keystore issues the self-signed
 * certificate whose hash is this device's id. Keystore keys are excluded from backups, so a restored or new
 * phone gets a new identity and has to be paired again.
 */
@ExercisedOnDevice(coveredBy = "core:crypto KeystoreIdentityTest")
@Singleton
class KeystoreIdentityProvider internal constructor(private val alias: String) : IdentityProvider {

    @Inject
    constructor() : this(DEFAULT_ALIAS)

    private val identity: DeviceIdentity by lazy { loadOrCreate() }

    override fun identity(): DeviceIdentity = identity

    private fun loadOrCreate(): DeviceIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(alias)) generate()
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return DeviceIdentity(entry.certificate as X509Certificate, entry.privateKey)
    }

    private fun generate() {
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        } catch (_: ProviderException) {
            // Some StrongBox implementations reject the spec with a generic error instead.
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=MediaSync device"))
            .setCertificateSerialNumber(BigInteger(SERIAL_BITS, SecureRandom()))
            .setCertificateNotBefore(Date(now - TimeUnit.DAYS.toMillis(1)))
            .setCertificateNotAfter(Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS)))
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /** Forgets this identity; the next [identity] call creates a new one. All pairings become invalid. */
    internal fun delete() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "mediasync-device-identity-v1"
        const val SERIAL_BITS = 64
        const val VALIDITY_DAYS = 30L * 365
    }
}
