/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.util.Lock

/**
 * Gives this device's [AppleIdentity] for the whole process: the saved identity when there is one, otherwise a new
 * one that is saved at once. The result is cached, so every call returns the same identity.
 *
 * A saved identity that cannot be read (its bytes are cut short or changed, or the key does not match the
 * certificate) is replaced by a new one and logged: the device id changes, so existing pairings must be done again.
 * Any other store failure (the Keychain locked before the first unlock, an I/O error) is thrown: it must not replace
 * a valid identity.
 */
class AppleIdentityProvider internal constructor(private val store: AppleIdentityStore, private val log: IdentityLog) {
    private val lock = Lock()
    private var cached: AppleIdentity? = null

    /** The name of the store the identity is kept in: "keychain" or "file". */
    internal val storeName: String get() = store.name

    /** This device's identity; loads it from the store, or creates and saves a new one. */
    fun identity(): AppleIdentity = lock.withLock {
        cached ?: loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): AppleIdentity {
        var replacing = false
        val saved = try {
            store.load()
        } catch (error: CorruptIdentityException) {
            log("the saved identity is unreadable (${error.reason})")
            replacing = true
            null
        }
        if (saved != null) {
            try {
                val identity = AppleIdentityFactory.identityFrom(saved)
                log("the identity was loaded from the ${store.name} store")
                return identity
            } catch (error: CorruptIdentityException) {
                log("the saved identity cannot be used (${error.reason})")
                replacing = true
            }
        }
        if (replacing) {
            log("a new identity replaces it: this device's id changes, so existing pairings must be done again")
        }
        val created = AppleIdentityFactory.createMaterial()
        store.save(created)
        log("a new identity was saved in the ${store.name} store")
        return AppleIdentityFactory.identityFrom(created)
    }

    companion object {
        /**
         * The app's provider: the Keychain when this build may use it, otherwise Application Support, as
         * [ChoosingIdentityStore] decides.
         */
        fun forThisApp(): AppleIdentityProvider = AppleIdentityProvider(
            store = ChoosingIdentityStore(
                keychain = KeychainIdentityStore(SecurityKeychain()),
                fallback = FileIdentityStore.applicationSupport(),
                log = diagnosticIdentityLog,
            ),
            log = diagnosticIdentityLog,
        )
    }
}
