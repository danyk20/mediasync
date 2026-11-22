/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import java.net.Inet4Address
import java.util.UUID
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Real JmDNS between two instances on this computer's first LAN address. Needs a multicast-capable interface: a
 * computer with none is skipped, and so is a CI runner (`CI` set) where the announcement never arrives.
 */
class JmdnsDiscoveryTest {

    @Test
    fun `one instance finds the service another one advertises`() {
        val network = DesktopLocalNetwork()
        val address = network.localAddresses().firstOrNull { it is Inet4Address }
        assumeTrue(address != null, "No LAN IPv4 address on this computer, so there is no multicast to test with.")

        val serviceId = UUID.randomUUID().toString().take(16)
        val port = 40_000 + (serviceId.hashCode() and 0x3FFF)
        val discovery = DesktopServiceDiscovery(network)
        val advertised = discovery.advertise(port, serviceId)
        try {
            val found = runBlocking { discovery.find(serviceId, 15_000) }
            if (found == null && System.getenv("CI") != null) {
                assumeTrue(false, "This CI runner does not loop multicast back; run it on a real network.")
            }
            // Another interface's address can come back too: any of this computer's LAN addresses is right.
            found shouldBeIn network.localAddresses().map { "${it.hostAddress}:$port" }
        } finally {
            advertised.close()
        }
    }

    @Test
    fun `maps a resolved JmDNS service to a peer`() {
        val info = ServiceInfo.create("_mediasync._tcp.local.", "MediaSync", 4242, 0, 0, mapOf("id" to "abc"))

        val peer = info.toPeer()

        peer.txt shouldBe mapOf("id" to "abc")
        peer.port shouldBe 4242
        peer.addresses shouldBe emptyList()
    }
}
