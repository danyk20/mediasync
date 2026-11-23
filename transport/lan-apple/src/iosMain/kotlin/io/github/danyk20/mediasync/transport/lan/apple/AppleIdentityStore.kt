/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics

/** Where the identity log lines go. The default is the diagnostic log; tests pass their own. */
internal typealias IdentityLog = (String) -> Unit

/** The identity log line for the diagnostic log. It never carries key material. */
internal val diagnosticIdentityLog: IdentityLog = { message -> Diagnostics.log(Area.APP, "identity: $message") }

/**
 * Keeps this device's identity material between launches. [load] returns null when nothing is saved; [save] replaces
 * whatever is saved; [delete] removes it.
 *
 * Implementations must throw [CorruptIdentityException] only for saved bytes that are unreadable. Any other failure
 * (the Keychain locked before the first unlock, an I/O error) is thrown as is: the caller must not treat it as
 * "no identity", or it would replace a valid identity and lose every pairing.
 */
internal interface AppleIdentityStore {
    /** The store's name in the log: "keychain" or "file". */
    val name: String

    fun load(): AppleIdentityMaterial?

    fun save(material: AppleIdentityMaterial)

    fun delete()
}

/**
 * Picks the Keychain store first. When the Keychain refuses this process, it switches to [fallback] for good and
 * retries that operation there. A refusal is errSecMissingEntitlement (-34018) for a build without the keychain-access
 * entitlement, or errSecNotAvailable (-25291), which a bare simulator test process gets on its first read. Other
 * Keychain errors pass through unchanged: a locked Keychain must not replace the identity.
 */
internal class ChoosingIdentityStore(
    private val keychain: AppleIdentityStore,
    private val fallback: AppleIdentityStore,
    private val log: IdentityLog,
) : AppleIdentityStore {
    private var current: AppleIdentityStore = keychain

    override val name: String get() = current.name

    /**
     * The saved identity: the Keychain's, or else the file store's. A save that the Keychain refused in an earlier
     * launch left the identity in the file, so an empty Keychain must not mean "no identity": the file is read, and
     * when it holds one the file store is used from then on. The identity is not moved into the Keychain here.
     */
    override fun load(): AppleIdentityMaterial? {
        val saved = attempt { it.load() }
        if (saved != null || current === fallback) return saved
        val fromFile = fallback.load() ?: return null
        current = fallback
        log("the identity was found in the file store, so the file store is used from now on")
        return fromFile
    }

    /**
     * After a save that the Keychain accepted, the file store's copy is removed, so a stale file identity cannot take
     * over later when the Keychain refuses a read. That removal is best-effort and never fails the save.
     */
    override fun save(material: AppleIdentityMaterial) {
        val savedToKeychain = attempt { store ->
            store.save(material)
            store === keychain
        }
        if (savedToKeychain) clearBestEffort(fallback)
    }

    /** Clears both stores, each best-effort: a failure in one never stops the other from being cleared. */
    override fun delete() {
        clearBestEffort(keychain)
        clearBestEffort(fallback)
    }

    private fun clearBestEffort(store: AppleIdentityStore) {
        runCatching { store.delete() }.onFailure { error ->
            log("the ${store.name} store could not be cleared: ${error.message}")
        }
    }

    private fun <T> attempt(operation: (AppleIdentityStore) -> T): T {
        if (current !== keychain) return operation(current)
        return try {
            operation(keychain)
        } catch (error: KeychainException) {
            if (error.status !in REFUSED_STATUSES) throw error
            current = fallback
            log(
                "the Keychain refuses this build (${error.operation}: ${error.status}), " +
                    "so the identity is kept in the file store",
            )
            operation(fallback)
        }
    }

    private companion object {
        /** errSecMissingEntitlement (-34018) and errSecNotAvailable (-25291): this process has no usable keychain. */
        val REFUSED_STATUSES = setOf(-34018, -25291)
    }
}
