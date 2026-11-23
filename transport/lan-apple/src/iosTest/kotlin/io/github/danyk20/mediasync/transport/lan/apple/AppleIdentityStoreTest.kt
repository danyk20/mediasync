/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.stat

class AppleIdentityStoreTest {
    @Test
    fun fileStoreRoundTripKeepsTheDeviceIdAndSignsAfterReload() {
        inTempDirectory { dir ->
            val first = AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity()
            val reloaded = AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity()

            assertEquals(first.id, reloaded.id)
            assertTrue(signatureVerifies(reloaded), "The reloaded key must sign for the saved certificate")
        }
    }

    @Test
    fun aChangedByteInTheFileTriggersRegeneration() {
        inTempDirectory { dir ->
            val original = AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity()
            val file = identityFile(dir)
            val bytes = FileSystem.SYSTEM.read(file) { readByteArray() }
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            FileSystem.SYSTEM.write(file) { write(bytes) }

            assertRegeneratesFrom(dir, original.id)
        }
    }

    @Test
    fun aTruncatedFileTriggersRegeneration() {
        inTempDirectory { dir ->
            val original = AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity()
            val file = identityFile(dir)
            val bytes = FileSystem.SYSTEM.read(file) { readByteArray() }
            FileSystem.SYSTEM.write(file) { write(bytes.copyOf(bytes.size - TRUNCATED_BY)) }

            assertRegeneratesFrom(dir, original.id)
        }
    }

    @Test
    fun theChooserFallsBackToTheFileStoreOnErrSecMissingEntitlement() {
        inTempDirectory { dir ->
            val keychain = MemoryKeychain().apply { failure = MISSING_ENTITLEMENT }
            val messages = mutableListOf<String>()
            val provider = AppleIdentityProvider(chooser(keychain, dir) { messages += it }, { messages += it })

            val identity = provider.identity()

            assertEquals("file", provider.storeName)
            assertTrue(keychain.items.isEmpty(), "Nothing may be kept in a Keychain that refused")
            assertTrue(FileSystem.SYSTEM.exists(identityFile(dir)), "The identity must be saved in the file store")
            assertTrue(messages.any { "file store" in it }, "The fallback must be logged: $messages")
            assertEquals(identity.id, AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity().id)
        }
    }

    @Test
    fun theChooserAlsoFallsBackWhenNoKeychainIsAvailable() {
        inTempDirectory { dir ->
            val keychain = MemoryKeychain().apply { failure = NO_KEYCHAIN }
            val provider = AppleIdentityProvider(chooser(keychain, dir, NO_LOG), NO_LOG)

            provider.identity()

            assertEquals("file", provider.storeName)
        }
    }

    @Test
    fun theKeychainKeepsTheIdentityWhenItIsAvailable() {
        inTempDirectory { dir ->
            val keychain = MemoryKeychain()
            val first = AppleIdentityProvider(chooser(keychain, dir, NO_LOG), NO_LOG)
            val id = first.identity().id

            assertEquals("keychain", first.storeName)
            assertEquals(2, keychain.items.size)
            assertFalse(FileSystem.SYSTEM.exists(identityFile(dir)), "The file store must stay unused")
            val reloaded = AppleIdentityProvider(chooser(keychain, dir, NO_LOG), NO_LOG).identity()
            assertEquals(id, reloaded.id)
            assertTrue(signatureVerifies(reloaded))
        }
    }

    @Test
    fun theChooserPicksTheFileStoreInTheSimulatorTestEnvironment() {
        inTempDirectory { dir ->
            val messages = mutableListOf<String>()
            val keychain = KeychainIdentityStore(SecurityKeychain(TEST_KEYCHAIN_SERVICE))
            val provider = AppleIdentityProvider(
                ChoosingIdentityStore(keychain, FileIdentityStore(dir)) { messages += it },
                { messages += it },
            )
            try {
                val identity = provider.identity()

                // A bare simulator test process has no usable keychain: its first SecItem read returns -25291.
                assertEquals("file", provider.storeName)
                assertTrue(messages.any { "file store" in it }, "The choice must be logged: $messages")
                assertEquals(identity.id, AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity().id)
            } finally {
                runCatching { keychain.delete() }
            }
        }
    }

    @Test
    fun aPartialKeychainIdentityTriggersRegeneration() {
        inTempDirectory { dir ->
            val keychain = MemoryKeychain()
            val original = AppleIdentityProvider(KeychainIdentityStore(keychain), NO_LOG).identity()
            keychain.items.remove(KeychainIdentityStore.CERTIFICATE_ACCOUNT)
            val messages = mutableListOf<String>()

            val regenerated = AppleIdentityProvider(KeychainIdentityStore(keychain)) { messages += it }.identity()

            assertNotEquals(original.id, regenerated.id)
            assertTrue(messages.any { "half of the identity" in it }, "The partial identity must be logged: $messages")
            assertEquals(regenerated.id, AppleIdentityProvider(KeychainIdentityStore(keychain), NO_LOG).identity().id)
        }
    }

    @Test
    fun aTransientKeychainErrorLeavesTheIdentityAlone() {
        val keychain = MemoryKeychain()
        val original = AppleIdentityProvider(KeychainIdentityStore(keychain), NO_LOG).identity()
        val savedKey = keychain.items.getValue(KeychainIdentityStore.KEY_ACCOUNT).copyOf()
        keychain.failure = INTERACTION_NOT_ALLOWED

        assertFailsWith<KeychainException> {
            AppleIdentityProvider(KeychainIdentityStore(keychain), NO_LOG).identity()
        }

        keychain.failure = null
        assertContentEquals(savedKey, keychain.items.getValue(KeychainIdentityStore.KEY_ACCOUNT))
        assertEquals(original.id, AppleIdentityProvider(KeychainIdentityStore(keychain), NO_LOG).identity().id)
    }

    @Test
    fun theSavedIdentityFileIsOwnerOnly() {
        inTempDirectory { dir ->
            AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity()

            assertEquals(OWNER_ONLY, fileMode(identityFile(dir).toString()))
        }
    }

    @Test
    fun anIdentityRefusedByTheKeychainOnSaveIsFoundInTheFileOnTheNextLaunch() {
        inTempDirectory { dir ->
            val refusingKeychain = MemoryKeychain().apply { writeFailure = MISSING_ENTITLEMENT }
            val first = AppleIdentityProvider(chooser(refusingKeychain, dir, NO_LOG), NO_LOG)
            val id = first.identity().id
            assertEquals("file", first.storeName)
            assertTrue(refusingKeychain.items.isEmpty(), "Nothing may be kept in a Keychain that refused")

            // The next launch: the Keychain works but is empty. The identity must come from the file, not a new one.
            val workingKeychain = MemoryKeychain()
            val messages = mutableListOf<String>()
            val second = AppleIdentityProvider(chooser(workingKeychain, dir) { messages += it }, { messages += it })
            val reloaded = second.identity()

            assertEquals(id, reloaded.id)
            assertEquals("file", second.storeName)
            assertTrue(workingKeychain.items.isEmpty(), "A new identity must not be saved in the Keychain")
            assertTrue(messages.any { "file store" in it }, "The file identity must be logged: $messages")
            assertTrue(signatureVerifies(reloaded))
        }
    }

    @Test
    fun aKeychainSaveRemovesAStaleFileIdentity() {
        inTempDirectory { dir ->
            val stale = AppleIdentityFactory.createMaterial()
            val fresh = AppleIdentityFactory.createMaterial()
            FileIdentityStore(dir).save(stale)
            val keychain = MemoryKeychain()
            val store = chooser(keychain, dir, NO_LOG)

            store.save(fresh)

            assertNull(FileIdentityStore(dir).load(), "The file store must be empty after a Keychain save")
            assertContentEquals(fresh.certificateDer, checkNotNull(store.load()).certificateDer)
        }
    }

    @Test
    fun whenTheKeychainHoldsOneIdentityAndTheFileAnotherTheKeychainWins() {
        inTempDirectory { dir ->
            val inKeychain = AppleIdentityFactory.createMaterial()
            val inFile = AppleIdentityFactory.createMaterial()
            val keychain = MemoryKeychain()
            KeychainIdentityStore(keychain).save(inKeychain)
            FileIdentityStore(dir).save(inFile)

            val loaded = checkNotNull(chooser(keychain, dir, NO_LOG).load())

            assertContentEquals(inKeychain.certificateDer, loaded.certificateDer)
        }
    }

    @Test
    fun deletingClearsBothStores() {
        inTempDirectory { dir ->
            val keychain = MemoryKeychain()
            val store = chooser(keychain, dir, NO_LOG)
            KeychainIdentityStore(keychain).save(AppleIdentityFactory.createMaterial())
            FileIdentityStore(dir).save(AppleIdentityFactory.createMaterial())

            store.delete()

            assertTrue(keychain.items.isEmpty(), "The Keychain must be cleared")
            assertNull(FileIdentityStore(dir).load(), "The file store must be cleared")
            assertNull(store.load())
        }
    }

    @Test
    fun aMismatchedKeyAndCertificateRegeneratesAndIsLogged() {
        val saved = AppleIdentityFactory.createMaterial()
        val other = AppleIdentityFactory.createMaterial()
        val store = HeldMaterialStore(AppleIdentityMaterial(saved.privateKey, other.certificateDer))
        val messages = mutableListOf<String>()

        val regenerated = AppleIdentityProvider(store) { messages += it }.identity()

        assertNotEquals(DeviceId.of(other.certificateDer.sha256()), regenerated.id)
        assertTrue(messages.any { "cannot be used" in it }, "The mismatch must be logged: $messages")
        assertEquals(regenerated.id, AppleIdentityProvider(store, NO_LOG).identity().id)
    }

    @Test
    fun aCertificateTheSecurityFrameworkRejectsPropagatesAsItIs() {
        val material = AppleIdentityFactory.createMaterial()
        // A SEQUENCE that holds the key's public point but is not a certificate: the pair matches, so only the
        // framework's refusal of the certificate can stop it.
        val notACertificate = byteArrayOf(0x30, NOT_A_CERTIFICATE_LENGTH) +
            material.privateKey.copyOfRange(0, PUBLIC_POINT_BYTES)
        val store = HeldMaterialStore(AppleIdentityMaterial(material.privateKey, notACertificate))

        assertFailsWith<AppleLanException> { AppleIdentityProvider(store, NO_LOG).identity() }

        assertContentEquals(notACertificate, checkNotNull(store.material).certificateDer, "It must not be replaced")
    }

    /** A provider on [dir] must find the file corrupt: a new identity, logged, and then the same one again. */
    private fun assertRegeneratesFrom(dir: String, originalId: DeviceId) {
        val messages = mutableListOf<String>()
        val regenerated = AppleIdentityProvider(FileIdentityStore(dir)) { messages += it }.identity()

        assertNotEquals(originalId, regenerated.id)
        assertTrue(messages.any { "unreadable" in it }, "The corrupt file must be logged: $messages")
        assertEquals(regenerated.id, AppleIdentityProvider(FileIdentityStore(dir), NO_LOG).identity().id)
    }

    private fun identityFile(dir: String) = "$dir/identity-v1".toPath()

    private fun chooser(keychain: KeychainBackend, dir: String, log: IdentityLog) =
        ChoosingIdentityStore(KeychainIdentityStore(keychain), FileIdentityStore(dir), log)

    /** The file mode bits of [path], as stat reports them: the permission bits only. */
    private fun fileMode(path: String): Int = memScoped {
        val info = alloc<stat>()
        check(stat(path, info.ptr) == 0) { "stat failed for $path" }
        info.st_mode.toInt() and PERMISSION_BITS
    }

    /** One identity held as bytes, for a provider to load and save. */
    private class HeldMaterialStore(var material: AppleIdentityMaterial?) : AppleIdentityStore {
        override val name: String get() = "held"

        override fun load(): AppleIdentityMaterial? = material

        override fun save(material: AppleIdentityMaterial) {
            this.material = material
        }

        override fun delete() {
            material = null
        }
    }

    /** A Keychain in memory. [failure] fails every call with its status (null: works); [writeFailure] only adds. */
    private class MemoryKeychain : KeychainBackend {
        val items = mutableMapOf<String, ByteArray>()
        var failure: Int? = null
        var writeFailure: Int? = null

        override fun read(account: String): ByteArray? {
            failure?.let { throw KeychainException("read", it) }
            return items[account]
        }

        override fun write(account: String, data: ByteArray) {
            (writeFailure ?: failure)?.let { throw KeychainException("add", it) }
            items[account] = data
        }

        override fun remove(account: String) {
            failure?.let { throw KeychainException("delete", it) }
            items.remove(account)
        }
    }

    private companion object {
        const val MISSING_ENTITLEMENT = -34018
        const val INTERACTION_NOT_ALLOWED = -25308
        const val NO_KEYCHAIN = -25291
        const val TRUNCATED_BY = 40
        const val TEST_KEYCHAIN_SERVICE = "io.github.danyk20.mediasync.lan-identity.test"
        const val OWNER_ONLY = 0x180 // 0600
        const val PERMISSION_BITS = 0x1FF // 0777
        const val NOT_A_CERTIFICATE_LENGTH: Byte = 0x41 // 65: the length of the public point that follows
        const val PUBLIC_POINT_BYTES = 65
    }
}
