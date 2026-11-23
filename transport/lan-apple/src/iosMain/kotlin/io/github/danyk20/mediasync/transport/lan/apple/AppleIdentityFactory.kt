/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlin.time.Clock
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecIdentityCreate
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrIsExtractable
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecRandomDefault
import platform.Security.sec_identity_create
import platform.Security.sec_identity_t
import platform.posix.arc4random_buf

/**
 * Makes this device's identity in memory: a P-256 key from Security, a certificate built by [buildSelfSignedP256Certificate]
 * and signed by the key, and a Security identity from both. Nothing is stored and no keychain entitlement is needed:
 * `SecIdentityCreate` works from a key and a certificate that are not in a keychain.
 *
 * Primary: `SecIdentityCreate(nil, cert, key)` (verified in the simulator). Fallback: a PKCS#12 import, not written
 * yet (only needed if the primary fails on a device; see the report).
 */
internal object AppleIdentityFactory {
    /** A new identity that lives in memory only. */
    fun create(): AppleIdentity = identityFrom(createMaterial())

    /**
     * A new identity as bytes, for a store to keep: a fresh P-256 key, its exported private key, and a certificate
     * the key signs.
     */
    fun createMaterial(): AppleIdentityMaterial {
        val key = generatePrivateKey()
        try {
            val publicKey = SecKeyCopyPublicKey(key) ?: throw AppleLanException("SecKeyCopyPublicKey returned NULL")
            val x963 = try {
                exportX963(publicKey)
            } finally {
                release(publicKey)
            }
            val der = buildSelfSignedP256Certificate(
                publicKeyX963 = x963,
                nowMillis = Clock.System.now().toEpochMilliseconds(),
                serial = randomSerial(),
                sign = { tbs -> signEcdsaSha256(key, tbs) },
            )
            return AppleIdentityMaterial(exportPrivateKey(key), der)
        } finally {
            release(key)
        }
    }

    /**
     * The identity that [material] holds. Rebuilds the private key from its bytes and checks that it belongs to the
     * certificate (the certificate must carry the key's public point), so a mismatched pair is refused.
     */
    fun identityFrom(material: AppleIdentityMaterial): AppleIdentity {
        // A copy, so the material's own bytes survive for its owner; the copy is zeroed once the key is built.
        val keyBytes = material.privateKey.copyOf()
        try {
            val key = keyFrom(keyBytes)
            try {
                val publicKey = SecKeyCopyPublicKey(key) ?: throw AppleLanException("SecKeyCopyPublicKey returned NULL")
                val x963 = try {
                    exportX963(publicKey)
                } finally {
                    release(publicKey)
                }
                if (!material.certificateDer.containsSubarray(x963)) {
                    throw IdentityMismatchException("the private key does not belong to the certificate")
                }
                return AppleIdentity(material.certificateDer, secIdentityFor(material.certificateDer, key))
            } finally {
                release(key)
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * The private key as ANSI X9.63 bytes (`04 || X || Y || K`). The key must be extractable, see [generatePrivateKey].
     */
    private fun exportPrivateKey(key: SecKeyRef): ByteArray = memScoped {
        val error = alloc<CFErrorRefVar>()
        val data = SecKeyCopyExternalRepresentation(key, error.ptr)
            ?: throw cfError("SecKeyCopyExternalRepresentation (private)", error.value)
        try {
            data.toByteArray()
        } finally {
            release(data)
        }
    }

    /** The private key that [bytes] (ANSI X9.63) stand for, as a non-permanent Security key. */
    private fun keyFrom(bytes: ByteArray): SecKeyRef = memScoped {
        val data = bytes.toCFData()
        val attributes = cfDictionary(
            listOf(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
            ),
        )
        try {
            val error = alloc<CFErrorRefVar>()
            SecKeyCreateWithData(data, attributes, error.ptr)
                ?: throw IdentityMismatchException(
                    "SecKeyCreateWithData rejected the saved private key: " +
                        cfError("SecKeyCreateWithData", error.value).message,
                )
        } finally {
            release(attributes)
            release(data)
        }
    }

    /** A P-256 key that is not stored: Security keeps it in memory for this process only, and it can be exported. */
    private fun generatePrivateKey(): SecKeyRef = memScoped {
        val attributes =
            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                ?: throw AppleLanException("CFDictionaryCreateMutable returned NULL")
        val size = alloc<IntVar>().apply { value = P256_BITS }
        val sizeNumber = CFNumberCreate(null, kCFNumberIntType, size.ptr)
        try {
            CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            CFDictionaryAddValue(attributes, kSecAttrKeySizeInBits, sizeNumber)
            // Not permanent: the key is never written to the keychain. kCFBooleanFalse is a constant, not released.
            CFDictionaryAddValue(attributes, kSecAttrIsPermanent, kCFBooleanFalse)
            // Extractable: the private key is exported into the identity store (SecKeyCopyExternalRepresentation).
            CFDictionaryAddValue(attributes, kSecAttrIsExtractable, kCFBooleanTrue)
            val error = alloc<CFErrorRefVar>()
            val key = SecKeyCreateRandomKey(attributes, error.ptr)
            key ?: throw cfError("SecKeyCreateRandomKey", error.value)
        } finally {
            release(sizeNumber)
            release(attributes)
        }
    }

    /** The uncompressed point `04 || X || Y` of a public key. */
    private fun exportX963(publicKey: SecKeyRef): ByteArray = memScoped {
        val error = alloc<CFErrorRefVar>()
        val data = SecKeyCopyExternalRepresentation(publicKey, error.ptr)
            ?: throw cfError("SecKeyCopyExternalRepresentation", error.value)
        try {
            data.toByteArray()
        } finally {
            release(data)
        }
    }

    /** ecdsa-with-SHA256 over [tbs]: the X9.62 DER signature that the certificate carries. */
    private fun signEcdsaSha256(key: SecKeyRef, tbs: ByteArray): ByteArray = memScoped {
        val input = tbs.toCFData()
        try {
            val error = alloc<CFErrorRefVar>()
            val signature =
                SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, input, error.ptr)
                    ?: throw cfError("SecKeyCreateSignature", error.value)
            try {
                signature.toByteArray()
            } finally {
                release(signature)
            }
        } finally {
            release(input)
        }
    }

    /** `SecIdentityCreate` then `sec_identity_create`, which returns a retained wrapper for Network.framework. */
    private fun secIdentityFor(der: ByteArray, key: SecKeyRef): sec_identity_t {
        val data = der.toCFData()
        try {
            val certificate = SecCertificateCreateWithData(null, data)
                .orFail("SecCertificateCreateWithData rejected the certificate")
            try {
                val identity = SecIdentityCreate(null, certificate, key)
                    .orFail("SecIdentityCreate returned NULL: the key does not match the certificate")
                try {
                    return sec_identity_create(identity).orFail("sec_identity_create returned nil")
                } finally {
                    release(identity)
                }
            } finally {
                release(certificate)
            }
        } finally {
            release(data)
        }
    }

    /**
     * 8 random bytes for the serial. SecRandomCopyBytes is the primary source; arc4random_buf is the fallback when
     * it fails (it reports an OSStatus, and the serial only needs to be unique, not secret).
     */
    private fun randomSerial(): ByteArray {
        val bytes = ByteArray(SERIAL_BYTES)
        val status = bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), pinned.addressOf(0))
        }
        if (status != errSecSuccess) {
            bytes.usePinned { pinned -> arc4random_buf(pinned.addressOf(0), bytes.size.toULong()) }
        }
        return bytes
    }

    private const val P256_BITS = 256
    private const val SERIAL_BYTES = 8
}

/** An error from a CFError out-parameter, with its code and description when there is one. */
internal fun cfError(operation: String, error: CFErrorRef?): AppleLanException {
    if (error == null) return AppleLanException("$operation failed without an error")
    val code = CFErrorGetCode(error)
    val text = CFErrorCopyDescription(error)?.let { description ->
        try {
            description.toKotlinString()
        } finally {
            release(description)
        }
    }
    release(error)
    return AppleLanException("$operation failed: code $code${text?.let { " ($it)" }.orEmpty()}")
}
