/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanServer
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import io.github.danyk20.mediasync.transport.lan.testIdentity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

class DesktopLocalNetworkTest {

    private class Fake(
        override val name: String,
        vararg addresses: Pair<String, Int>,
        override val displayName: String = name,
        override val isUp: Boolean = true,
        override val isLoopback: Boolean = false,
        override val isPointToPoint: Boolean = false,
        override val isVirtual: Boolean = false,
    ) : LocalInterface {
        override val addresses = addresses.map { (text, prefix) -> InetAddress.getByName(text) to prefix }
    }

    private fun network(vararg nics: LocalInterface) = DesktopLocalNetwork(interfaces = { nics.toList() })

    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `lists the addresses of an Ethernet or Wi-Fi interface, IPv4 first`() {
        val wifi = Fake("en0", "fd00::5" to 64, "192.168.1.20" to 24)

        network(wifi).localAddresses() shouldBe listOf(ip("192.168.1.20"), ip("fd00::5"))
    }

    @Test
    fun `skips down, loopback, point-to-point and virtual interfaces`() {
        val all = arrayOf(
            Fake("en1", "10.0.0.2" to 24, isUp = false),
            Fake("lo0", "127.0.0.1" to 8, isLoopback = true),
            Fake("ppp0", "10.9.9.9" to 32, isPointToPoint = true),
            Fake("eth0:1", "10.0.0.3" to 24, isVirtual = true),
        )

        network(*all).localAddresses().shouldBeEmpty()
    }

    @Test
    fun `skips tunnels, bridges and virtual switches by name`() {
        val all = arrayOf(
            Fake("utun3", "10.8.0.2" to 24),
            Fake("awdl0", "fe80::1" to 64),
            Fake("docker0", "172.17.0.1" to 16),
            Fake("bridge100", "192.168.64.1" to 24),
            Fake("eth5", "192.168.99.1" to 24, displayName = "VMware Virtual Ethernet Adapter"),
        )

        network(*all).localAddresses().shouldBeEmpty()
    }

    @Test
    fun `skips link-local, loopback and multicast addresses of a usable interface`() {
        val wifi = Fake(
            "en0",
            "fe80::1" to 64,
            "169.254.3.4" to 16,
            "127.0.0.2" to 8,
            "224.0.0.1" to 4,
            "10.1.1.1" to 24,
        )

        network(wifi).localAddresses() shouldBe listOf(ip("10.1.1.1"))
    }

    @Test
    fun `an address is on the local network when it shares a subnet with a usable interface`() {
        val net = network(Fake("en0", "192.168.1.20" to 24, "fd00::5" to 64), Fake("utun1", "10.8.0.2" to 24))

        net.isOnLocalNetwork(ip("192.168.1.99")) shouldBe true
        net.isOnLocalNetwork(ip("192.168.2.99")) shouldBe false
        net.isOnLocalNetwork(ip("fd00::77")) shouldBe true
        net.isOnLocalNetwork(ip("fd01::77")) shouldBe false
        net.isOnLocalNetwork(ip("10.8.0.9")) shouldBe false // Behind the VPN, not on the local network.
    }

    @Test
    fun `with loopback allowed, 127_0_0_1 is a local address and on the local network`() {
        val lo = Fake("lo0", "127.0.0.1" to 8, isLoopback = true)
        val net = DesktopLocalNetwork(interfaces = { listOf(lo) }, allowLoopback = true)

        net.localAddresses() shouldBe listOf(ip("127.0.0.1"))
        net.isOnLocalNetwork(ip("127.0.0.1")) shouldBe true
    }

    @Test
    fun `subnets that are not byte aligned compare bit by bit`() {
        DesktopLocalNetwork.sameSubnet(ip("10.0.0.1"), ip("10.0.127.254"), 17) shouldBe true
        DesktopLocalNetwork.sameSubnet(ip("10.0.0.1"), ip("10.0.128.1"), 17) shouldBe false
        DesktopLocalNetwork.sameSubnet(ip("10.0.0.1"), ip("fd00::1"), 8) shouldBe false
    }

    @Test
    fun `the system's interfaces can be listed and keepingAwake just runs the session`() {
        val net = DesktopLocalNetwork()
        net.localAddresses() // Whatever this machine has; must not throw.
        net.keepingAwake { 42 } shouldBe 42
        net.canUseMoreConnections() shouldBe true
        LocalInterface.system().forEach { it.addresses }
    }

    @Test
    fun `two identities talk over loopback TLS through the sockets of this network`() {
        val desktop = testIdentity("desktop")
        val phone = testIdentity("phone")
        val timeouts = LanTimeouts(connectMillis = 2_000, readMillis = 5_000)
        val executor = Executors.newCachedThreadPool()
        val server = LanServer(desktop, { it == phone.id }, timeouts = timeouts)
        try {
            val accepted = executor.submit(Callable { server.accept().handshake() })
            val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)

            val client = LanClient.connect(
                address,
                phone,
                expectedPeer = desktop.id,
                timeouts,
                network = DesktopLocalNetwork().factoryFor(address),
            )
            val serverSide = accepted.get(5, TimeUnit.SECONDS)
            client.sink.writeUtf8("hello\n")
            client.sink.flush()

            serverSide.source.readUtf8Line() shouldBe "hello"
            serverSide.peerId shouldBe phone.id
            client.peerId shouldBe desktop.id
            client.close()
            serverSide.close()
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
