/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import org.junit.jupiter.api.Test

/** [JmdnsSession] over a mocked JmDNS: the registration, browse and mapping code without any network. */
class JmdnsSessionTest {

    private val jmdns = mockk<JmDNS>(relaxed = true)
    private val session = JmdnsSession(jmdns)

    @Test
    fun `registers the service with the Android type, name, port and id record`() {
        session.register(MdnsService("_mediasync._tcp.local.", "MediaSync", 4242, mapOf("id" to "abc")))

        val registered = slot<ServiceInfo>()
        verify { jmdns.registerService(capture(registered)) }
        registered.captured.type shouldBe "_mediasync._tcp.local."
        registered.captured.name shouldBe "MediaSync"
        registered.captured.port shouldBe 4242
        registered.captured.getPropertyString("id") shouldBe "abc"
    }

    @Test
    fun `browsing asks for the details of each service it sees and reports the resolved ones`() {
        val listener = slot<ServiceListener>()
        val found = mutableListOf<MdnsPeer>()
        val browse = session.browse("_mediasync._tcp.local.") { found += it }
        verify { jmdns.addServiceListener("_mediasync._tcp.local.", capture(listener)) }

        val added = mockk<ServiceEvent>()
        every { added.type } returns "_mediasync._tcp.local."
        every { added.name } returns "MediaSync"
        listener.captured.serviceAdded(added)
        verify { jmdns.requestServiceInfo("_mediasync._tcp.local.", "MediaSync", true) }
        found.size shouldBe 0

        val resolved = mockk<ServiceEvent>()
        every { resolved.info } returns
            ServiceInfo.create("_mediasync._tcp.local.", "MediaSync", 4242, 0, 0, mapOf("id" to "abc"))
        listener.captured.serviceResolved(resolved)
        found.map { it.txt } shouldContainExactly listOf(mapOf("id" to "abc"))
        found.single().port shouldBe 4242

        listener.captured.serviceRemoved(added)
        browse.close()
        verify { jmdns.removeServiceListener("_mediasync._tcp.local.", listener.captured) }
    }

    @Test
    fun `a resolved service that fails to map does not break the listener`() {
        val listener = slot<ServiceListener>()
        session.browse("_mediasync._tcp.local.") { error("consumer failed") }
        verify { jmdns.addServiceListener("_mediasync._tcp.local.", capture(listener)) }

        val resolved = mockk<ServiceEvent>()
        every { resolved.info } returns
            ServiceInfo.create("_mediasync._tcp.local.", "MediaSync", 4242, 0, 0, mapOf("id" to "abc"))
        listener.captured.serviceResolved(resolved)
    }

    @Test
    fun `a resolved service keeps its IPv4 and IPv6 addresses, and blank TXT values stay empty`() {
        val info = mockk<ServiceInfo>()
        every { info.propertyNames } returns Collections.enumeration(listOf("id", "flag"))
        every { info.getPropertyString("id") } returns "abc"
        every { info.getPropertyString("flag") } returns null
        every { info.port } returns 4242
        val v4 = InetAddress.getByName("192.168.1.7") as Inet4Address
        val v6 = InetAddress.getByName("fd00::9") as Inet6Address
        every { info.inet4Addresses } returns arrayOf(v4)
        every { info.inet6Addresses } returns arrayOf(v6)

        val peer = info.toPeer()

        peer.txt shouldBe mapOf("id" to "abc", "flag" to "")
        peer.port shouldBe 4242
        peer.addresses shouldContainExactly listOf(v4, v6)
    }

    @Test
    fun `close unregisters the services and closes JmDNS, even when unregistering fails`() {
        every { jmdns.unregisterAllServices() } throws IllegalStateException("already gone")

        session.close()

        verify { jmdns.close() }
    }
}
