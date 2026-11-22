/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.sync.android.AddressProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DesktopServiceDiscoveryTest {

    private fun ip(text: String) = InetAddress.getByName(text)

    private class FakeSession(
        val address: InetAddress,
        val peers: List<MdnsPeer> = emptyList(),
        private val hangOnClose: Boolean = false,
    ) : MdnsSession {
        val registered = CopyOnWriteArrayList<MdnsService>()
        var closed = 0
        var browseClosed = 0

        override fun register(service: MdnsService) {
            registered += service
        }

        override fun browse(type: String, found: (MdnsPeer) -> Unit): Closeable {
            peers.forEach(found)
            return Closeable { browseClosed++ }
        }

        override fun close() {
            closed++
            if (hangOnClose) CountDownLatch(1).await()
        }
    }

    private class FakeOpener(
        private val failing: Set<InetAddress> = emptySet(),
        val peersOf: (InetAddress) -> List<MdnsPeer> = {
            emptyList()
        },
        val hang: Boolean = false,
    ) : MdnsOpener {
        val sessions = CopyOnWriteArrayList<FakeSession>()

        override fun open(address: InetAddress): MdnsSession {
            if (address in failing) error("no multicast on $address")
            return FakeSession(address, peersOf(address), hang).also { sessions += it }
        }
    }

    private fun discovery(opener: MdnsOpener, vararg addresses: String, closeTimeout: Long = 2_000) =
        DesktopServiceDiscovery(AddressProvider { addresses.map(::ip) }, opener, closeTimeout)

    private fun peer(id: String?, port: Int, vararg addresses: String) = MdnsPeer(
        txt = if (id == null) emptyMap() else mapOf("id" to id),
        port = port,
        addresses = addresses.map(::ip),
    )

    @Test
    fun `advertises the Android service on every IPv4 address and closes them all`() {
        val opener = FakeOpener()
        val handle = discovery(opener, "192.168.1.20", "10.0.0.5", "fd00::5").advertise(4242, "abc123")
        handle.close() // waits for the background announcement

        opener.sessions.map { it.address } shouldContainExactlyInAnyOrder listOf(ip("192.168.1.20"), ip("10.0.0.5"))
        opener.sessions.forEach {
            it.registered.toList() shouldContainExactly listOf(
                MdnsService("_mediasync._tcp.local.", "MediaSync", 4242, mapOf("id" to "abc123")),
            )
            it.closed shouldBe 1
        }
    }

    @Test
    fun `uses an IPv6 address only when there is no IPv4 one`() {
        val opener = FakeOpener()
        discovery(opener, "fd00::5").advertise(1, "x").close()

        opener.sessions.map { it.address } shouldContainExactly listOf(ip("fd00::5"))
    }

    @Test
    fun `an interface that cannot open does not stop the others`() {
        val opener = FakeOpener(failing = setOf(ip("10.0.0.5")))
        discovery(opener, "10.0.0.5", "192.168.1.20").advertise(1, "x").close()

        opener.sessions.map { it.address } shouldContainExactly listOf(ip("192.168.1.20"))
        opener.sessions.single().registered.size shouldBe 1
    }

    @Test
    fun `advertising without an address, and closing twice, are harmless`() {
        val none = discovery(FakeOpener()).advertise(1, "x")
        none.close()
        none.close()

        val opener = FakeOpener()
        val handle = discovery(opener, "192.168.1.20").advertise(1, "x")
        handle.close()
        handle.close()
        opener.sessions.single().closed shouldBe 1
    }

    @Test
    fun `closing gives up on a session that hangs`() {
        val opener = FakeOpener(hang = true)
        val handle = discovery(opener, "192.168.1.20", closeTimeout = 300).advertise(1, "x")

        val started = System.nanoTime()
        handle.close()
        ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 2_000L
    }

    @Test
    fun `finds the receiver with the id, preferring IPv4`() = runBlocking<Unit> {
        val opener = FakeOpener(peersOf = {
            listOf(
                peer("other", 1111, "192.168.1.99"),
                peer(null, 2222, "192.168.1.98"),
                peer("wanted", 4242, "fe80::1", "fd00::9", "192.168.1.7"),
            )
        })

        discovery(opener, "192.168.1.20").find("wanted", 2_000) shouldBe "192.168.1.7:4242"
        opener.sessions.single().closed shouldBe 1
        opener.sessions.single().browseClosed shouldBe 1
    }

    @Test
    fun `formats an IPv6 receiver with brackets and skips link-local addresses`() = runBlocking<Unit> {
        val opener = FakeOpener(peersOf = { listOf(peer("wanted", 4242, "fe80::1", "fd00::9")) })

        discovery(opener, "192.168.1.20").find("wanted", 2_000) shouldBe "[fd00:0:0:0:0:0:0:9]:4242"
    }

    @Test
    fun `merges what the interfaces find`() = runBlocking<Unit> {
        val opener = FakeOpener(peersOf = { address ->
            if (address == ip("10.0.0.5")) listOf(peer("wanted", 4242, "10.0.0.9")) else emptyList()
        })

        discovery(opener, "192.168.1.20", "10.0.0.5").find("wanted", 2_000) shouldBe "10.0.0.9:4242"
        opener.sessions.forEach { it.closed shouldBe 1 }
    }

    @Test
    fun `finds nothing after the timeout and still closes everything`() = runBlocking<Unit> {
        val opener = FakeOpener(peersOf = { listOf(peer("other", 1, "192.168.1.99")) })

        discovery(opener, "192.168.1.20").find("wanted", 200) shouldBe null
        opener.sessions.single().closed shouldBe 1
    }

    @Test
    fun `finds nothing without addresses or when no interface opens`() = runBlocking<Unit> {
        discovery(FakeOpener()).find("wanted", 100) shouldBe null
        discovery(FakeOpener(failing = setOf(ip("10.0.0.5"))), "10.0.0.5").find("wanted", 100) shouldBe null
    }
}
