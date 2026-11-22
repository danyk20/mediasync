/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.transport.lan.DeviceIdentity
import io.github.danyk20.mediasync.transport.lan.IdentityProvider
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** The identity file exists but its password is gone: it cannot be opened and must not be silently replaced. */
class IdentityLockedException(message: String) : IOException(message)

/**
 * Keeps the desktop's device identity (docs/adr/0009): an EC P-256 key and a self-signed certificate, made once and
 * reused, in a PKCS#12 file in [directory]. Same certificate as the phones' (`CN=MediaSync device`, 30 years), so
 * phones accept the computer exactly as they accept each other. The file's random password lives in [preferred] (the
 * OS credential store); where that is unusable (a headless Linux has no Secret Service) it lives in [fallback], a
 * file with owner-only permissions. The private key never leaves the PKCS#12 file.
 *
 * Losing the file or its password means a new device id: every phone has to be paired again. A password that is
 * missing while the file exists throws [IdentityLockedException] instead of creating a new identity.
 */
class DesktopIdentityProvider(
    private val directory: Path,
    private val preferred: SecretStore,
    private val fallback: SecretStore = FileSecretStore(directory.resolve(PASSWORD_FILE)),
    private val log: (String) -> Unit = {},
) : IdentityProvider {

    private val identity: DeviceIdentity by lazy { loadOrCreate() }

    /** How the password is protected. Valid once [identity] has been called. */
    @Volatile
    var protection: SecretProtection? = null
        private set

    private val file: Path get() = directory.resolve(IDENTITY_FILE)

    override fun identity(): DeviceIdentity = identity

    private fun loadOrCreate(): DeviceIdentity = if (Files.isRegularFile(file)) load() else create()

    private fun load(): DeviceIdentity {
        val (password, store) = preferred.read()?.let { it to preferred }
            ?: fallback.read()?.let { it to fallback }
            ?: throw IdentityLockedException(
                "The device identity in $file cannot be opened: its password is not in the credential store " +
                    "or in ${directory.resolve(
                        PASSWORD_FILE,
                    )}. Delete the file to start over; every phone must then pair again.",
            )
        protection = store.protection
        log("Device identity unlocked, ${store.protection.description}")
        val keyStore = KeyStore.getInstance(PKCS12).apply {
            Files.newInputStream(file).use { load(it, password.toCharArray()) }
        }
        val key = keyStore.getKey(ALIAS, password.toCharArray()) as PrivateKey
        return DeviceIdentity(keyStore.getCertificate(ALIAS) as X509Certificate, key)
    }

    private fun create(): DeviceIdentity {
        val random = SecureRandom()
        val password = ByteArray(PASSWORD_BYTES).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }
        // Store the password first: a file whose password was never saved would be a lost identity.
        val store = savePassword(password)
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()
        val certificate = selfSigned(keyPair.public, keyPair.private, random)
        val keyStore = KeyStore.getInstance(PKCS12).apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, password.toCharArray(), arrayOf(certificate))
        }
        val bytes = java.io.ByteArrayOutputStream().also { keyStore.store(it, password.toCharArray()) }.toByteArray()
        writeOwnerOnly(file, bytes)
        protection = store.protection
        log("Device identity created, ${store.protection.description}")
        return DeviceIdentity(certificate, keyPair.private)
    }

    private fun savePassword(password: String): SecretStore {
        try {
            preferred.write(password)
            if (preferred.read() == password) return preferred
            log("The credential store did not keep the password; using a file with owner-only permissions")
        } catch (e: IOException) {
            log("The credential store is not usable (${e.message}); using a file with owner-only permissions")
        }
        fallback.write(password)
        return fallback
    }

    private fun selfSigned(
        publicKey: java.security.PublicKey,
        privateKey: PrivateKey,
        random: SecureRandom,
    ): X509Certificate {
        val subject = X500Name(SUBJECT)
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(SERIAL_BITS, random),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS)),
            subject,
            publicKey,
        )
        return JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)),
        )
    }

    companion object {
        const val IDENTITY_FILE = "identity.p12"
        const val PASSWORD_FILE = "identity.password"
        const val SUBJECT = "CN=MediaSync device"
        private const val ALIAS = "mediasync-device-identity-v1"
        private const val PKCS12 = "PKCS12"
        private const val PASSWORD_BYTES = 32
        private const val SERIAL_BITS = 64
        private const val VALIDITY_DAYS = 30L * 365
    }
}
