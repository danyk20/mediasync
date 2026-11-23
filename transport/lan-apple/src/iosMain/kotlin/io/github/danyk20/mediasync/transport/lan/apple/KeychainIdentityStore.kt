/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** A Security call failed with [status] (an OSStatus). [operation] names the call for the log. */
internal class KeychainException(val operation: String, val status: Int) :
    Exception("$operation failed with Security status $status")

/**
 * The Keychain operations [KeychainIdentityStore] needs, behind a seam so a test can force an error status. Each
 * account is one generic-password item under the same service.
 */
internal interface KeychainBackend {
    /** The data under [account], or null when there is no such item. Throws [KeychainException] on any other error. */
    fun read(account: String): ByteArray?

    /** Stores [data] under [account], replacing an item already there. */
    fun write(account: String, data: ByteArray)

    /** Removes the item under [account]. Does nothing when there is none. */
    fun remove(account: String)
}

/**
 * The real Keychain. Items are generic passwords under [service], readable only after the first unlock and only on
 * this device (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), so they are never copied to another device.
 */
internal class SecurityKeychain(private val service: String = IDENTITY_SERVICE) : KeychainBackend {
    override fun read(account: String): ByteArray? = cfScope {
        val query = dictionary(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to string(service),
            kSecAttrAccount to string(account),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        memScoped {
            val result = alloc<CFTypeRefVar>()
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecItemNotFound -> null

                errSecSuccess -> {
                    // With kSecReturnData and one match, SecItemCopyMatching returns the item's CFData.
                    @Suppress("UNCHECKED_CAST")
                    val data = result.value as CFDataRef?
                        ?: throw KeychainException("read", status)
                    try {
                        data.toByteArray()
                    } finally {
                        release(data)
                    }
                }

                else -> throw KeychainException("read", status)
            }
        }
    }

    override fun write(account: String, data: ByteArray) {
        remove(account)
        cfScope {
            val attributes = dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to string(service),
                kSecAttrAccount to string(account),
                kSecValueData to data(data),
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
            val status = SecItemAdd(attributes, null)
            if (status != errSecSuccess) throw KeychainException("add", status)
        }
    }

    override fun remove(account: String) {
        cfScope {
            val query = dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to string(service),
                kSecAttrAccount to string(account),
            )
            val status = SecItemDelete(query)
            if (status != errSecSuccess && status != errSecItemNotFound) throw KeychainException("delete", status)
        }
    }

    companion object {
        /** The service name of the identity items: this app's bundle-style name for the LAN identity. */
        const val IDENTITY_SERVICE = "io.github.danyk20.mediasync.lan-identity"
    }
}

/**
 * Keeps the identity in the Keychain as two generic-password items: `identity-key` (the 97-byte private key) and
 * `identity-certificate` (the DER certificate). Saving replaces both. Only one of them present is a partial identity
 * and reads as [CorruptIdentityException], so it is regenerated rather than mixed with a new half.
 */
internal class KeychainIdentityStore(private val keychain: KeychainBackend) : AppleIdentityStore {
    override val name: String get() = "keychain"

    override fun load(): AppleIdentityMaterial? {
        val key = keychain.read(KEY_ACCOUNT)
        val certificate = keychain.read(CERTIFICATE_ACCOUNT)
        return when {
            key == null && certificate == null -> null

            key == null || certificate == null ->
                throw CorruptIdentityException("only half of the identity is in the Keychain")

            else -> AppleIdentityMaterial(key, certificate)
        }
    }

    override fun save(material: AppleIdentityMaterial) {
        delete()
        try {
            keychain.write(KEY_ACCOUNT, material.privateKey)
            keychain.write(CERTIFICATE_ACCOUNT, material.certificateDer)
        } catch (error: KeychainException) {
            delete()
            throw error
        }
    }

    override fun delete() {
        keychain.remove(KEY_ACCOUNT)
        keychain.remove(CERTIFICATE_ACCOUNT)
    }

    companion object {
        const val KEY_ACCOUNT = "identity-key"
        const val CERTIFICATE_ACCOUNT = "identity-certificate"
    }
}

/**
 * Core Foundation objects that one Keychain call makes. [cfScope] releases them all when the call ends, however it
 * ends: a status error throws, and the objects must still be released.
 */
private class CfScope {
    private val owned = mutableListOf<CFTypeRef>()

    fun string(text: String): CFTypeRef = cfString(text).also { owned += it }

    fun data(bytes: ByteArray): CFTypeRef = bytes.toCFData().also { owned += it }

    fun dictionary(vararg entries: Pair<COpaquePointer?, COpaquePointer?>): CFDictionaryRef =
        cfDictionary(entries.toList()).also { owned += it }

    fun close() = owned.forEach(::release)
}

private inline fun <T> cfScope(block: CfScope.() -> T): T {
    val scope = CfScope()
    try {
        return scope.block()
    } finally {
        scope.close()
    }
}
