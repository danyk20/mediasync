/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanServer
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import io.github.danyk20.mediasync.transport.lan.testIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.interfaces.ECPublicKey
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Never touches the real credential store: [MemorySecretStore] and [BrokenSecretStore] stand in for it. */
class DesktopIdentityProviderTest {

    @TempDir
    lateinit var directory: Path

    private fun provider(preferred: SecretStore = MemorySecretStore(), log: (String) -> Unit = {}) =
        DesktopIdentityProvider(directory, preferred, log = log)

    @Test
    fun `the same device id comes back after a restart`() {
        val keychain = MemorySecretStore()
        val first = provider(keychain).identity()

        val second = provider(keychain).identity()

        second.id shouldBe first.id
        second.certificate shouldBe first.certificate
    }

    @Test
    fun `the certificate matches the phones' and verifies with its own key`() {
        val certificate = provider().identity().certificate

        certificate.verify(certificate.publicKey)
        certificate.sigAlgName shouldBe "SHA256withECDSA"
        certificate.subjectX500Principal.name shouldBe "CN=MediaSync device"
        certificate.issuerX500Principal shouldBe certificate.subjectX500Principal
        (certificate.publicKey as ECPublicKey).params.order.bitLength() shouldBe 256
        val years = (certificate.notAfter.time - certificate.notBefore.time) / TimeUnit.DAYS.toMillis(365)
        years shouldBe 30
    }

    @Test
    fun `the private key matches the certificate`() {
        val identity = provider().identity()
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(identity.privateKey)
        signature.update(byteArrayOf(1, 2, 3))
        val signed = signature.sign()
        signature.initVerify(identity.certificate)
        signature.update(byteArrayOf(1, 2, 3))
        signature.verify(signed) shouldBe true
    }

    @Test
    fun `the password goes to the credential store when there is one`() {
        val keychain = MemorySecretStore()
        val provider = provider(keychain)
        provider.identity()

        provider.protection shouldBe SecretProtection.OS_CREDENTIAL_STORE
        keychain.read() shouldNotBe null
        Files.exists(directory.resolve(DesktopIdentityProvider.PASSWORD_FILE)) shouldBe false
    }

    @Test
    fun `without a credential store the password is kept in an owner-only file and the identity is stable`() {
        val messages = mutableListOf<String>()
        val first = provider(BrokenSecretStore(), messages::add)
        val id = first.identity().id

        first.protection shouldBe SecretProtection.FILE_PERMISSIONS
        messages.joinToString() shouldContain "owner-only"
        val again = provider(BrokenSecretStore())
        again.identity().id shouldBe id
        again.protection shouldBe SecretProtection.FILE_PERMISSIONS
    }

    @Test
    fun `the identity and password files are readable by the owner only`() {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
        provider(BrokenSecretStore()).identity()

        listOf(DesktopIdentityProvider.IDENTITY_FILE, DesktopIdentityProvider.PASSWORD_FILE).forEach {
            Files.getPosixFilePermissions(directory.resolve(it)) shouldBe PosixFilePermissions.fromString("rw-------")
        }
    }

    @Test
    fun `a missing password does not silently create a new identity`() {
        provider(MemorySecretStore()).identity()

        val error = shouldThrow<IdentityLockedException> { provider(MemorySecretStore()).identity() }

        error.message!! shouldContain "pair again"
    }

    @Test
    fun `a credential store that forgets what it was given falls back to the file`() {
        val provider = provider(ForgetfulSecretStore())

        provider.identity()

        provider.protection shouldBe SecretProtection.FILE_PERMISSIONS
    }

    @Test
    fun `a paired phone and the desktop connect through certificate pinning`() {
        val desktop = provider().identity()
        val phone = testIdentity("phone")
        val timeouts = LanTimeouts(connectMillis = 2_000, readMillis = 5_000)
        val executor = Executors.newCachedThreadPool()
        val server = LanServer(desktop, { it == phone.id }, timeouts = timeouts)
        try {
            val accepted = executor.submit(Callable { server.accept().handshake() })
            val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)

            val client = LanClient.connect(address, phone, expectedPeer = desktop.id, timeouts)
            val serverSide = accepted.get(5, TimeUnit.SECONDS)

            client.peerId shouldBe desktop.id
            serverSide.peerId shouldBe phone.id
            client.close()
            serverSide.close()
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `the file secret store reads back what it wrote`() {
        val store = FileSecretStore(directory.resolve("secret"))
        store.read() shouldBe null

        store.write("abc")

        store.read() shouldBe "abc"
    }

    @Test
    fun `the keyring store reports a missing backend as an IOException`() {
        val store = KeyringSecretStore("mediasync-test", "device", keyring = { throw IllegalStateException("none") })

        store.read() shouldBe null
        shouldThrow<IOException> { store.write("abc") }
    }
}

private class MemorySecretStore : SecretStore {
    private var secret: String? = null
    override val protection = SecretProtection.OS_CREDENTIAL_STORE
    override fun read() = secret
    override fun write(secret: String) {
        this.secret = secret
    }
}

private class BrokenSecretStore : SecretStore {
    override val protection = SecretProtection.OS_CREDENTIAL_STORE
    override fun read(): String? = null
    override fun write(secret: String) = throw IOException("no Secret Service")
}

private class ForgetfulSecretStore : SecretStore {
    override val protection = SecretProtection.OS_CREDENTIAL_STORE
    override fun read(): String? = null
    override fun write(secret: String) = Unit
}
