/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFErrorRefVar
import platform.Foundation.NSTemporaryDirectory
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecIdentityCopyPrivateKey
import platform.Security.SecIdentityRef
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRefVar
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.sec_identity_copy_ref

/** Logs nothing: for the providers the tests build, whose log lines they do not read. */
internal val NO_LOG: IdentityLog = {}

/**
 * A fresh temporary directory for one test, passed to [block] and deleted afterwards, even when the test fails. The
 * path is unique per call, so tests never see each other's identity files.
 */
internal fun inTempDirectory(block: (String) -> Unit) {
    val directory = (NSTemporaryDirectory() + "mediasync-identity-" + Random.nextLong().toULong().toString()).toPath()
    FileSystem.SYSTEM.createDirectories(directory)
    try {
        block(directory.toString())
    } finally {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }
}

/** Whether [identity]'s private key, as Security holds it, makes a signature that its certificate verifies. */
internal fun signatureVerifies(identity: AppleIdentity): Boolean {
    val message = "the identity signs".encodeToByteArray()
    val signature = signWith(identity, message)
    return verifiesWithCertificate(identity.certificateDer, message, signature)
}

/** A signature over [message] by the private key of [identity]'s Security identity (X9.62 DER). */
private fun signWith(identity: AppleIdentity, message: ByteArray): ByteArray = memScoped {
    val identityRef: SecIdentityRef = sec_identity_copy_ref(identity.secIdentity)
        ?: throw AppleLanException("sec_identity_copy_ref returned NULL")
    val keyVar = alloc<SecKeyRefVar>()
    val status = SecIdentityCopyPrivateKey(identityRef, keyVar.ptr)
    check(status == 0) { "SecIdentityCopyPrivateKey failed with $status" }
    val key = keyVar.value ?: throw AppleLanException("SecIdentityCopyPrivateKey returned NULL")
    val input = message.toCFData()
    try {
        val error = alloc<CFErrorRefVar>()
        val signature = SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, input, error.ptr)
            ?: throw cfError("SecKeyCreateSignature", error.value)
        try {
            signature.toByteArray()
        } finally {
            release(signature)
        }
    } finally {
        release(input)
        release(key)
        release(identityRef)
    }
}

internal fun verifiesWithCertificate(certificateDer: ByteArray, signed: ByteArray, signature: ByteArray): Boolean {
    val certificateData = certificateDer.toCFData()
    val certificate = SecCertificateCreateWithData(null, certificateData)
        .orFail("SecCertificateCreateWithData rejected the certificate")
    try {
        val publicKey = SecCertificateCopyKey(certificate).orFail("SecCertificateCopyKey returned NULL")
        try {
            val signedData = signed.toCFData()
            val signatureData = signature.toCFData()
            try {
                return memScoped {
                    val error = alloc<CFErrorRefVar>()
                    val valid = SecKeyVerifySignature(
                        publicKey,
                        kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                        signedData,
                        signatureData,
                        error.ptr,
                    )
                    release(error.value)
                    valid
                }
            } finally {
                release(signatureData)
                release(signedData)
            }
        } finally {
            release(publicKey)
        }
    } finally {
        release(certificate)
        release(certificateData)
    }
}
