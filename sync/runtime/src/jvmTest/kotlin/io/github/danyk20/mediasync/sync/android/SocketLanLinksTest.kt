/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.lan.IdentityProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SocketLanLinksTest {
    private val home: InetAddress = InetAddress.getByName("10.128.0.57")
    private val identity = IdentityProvider { error("Not used here") }

    @Test
    fun anAddressOffTheNetworkIsNotLocal() {
        val sockets = object : LanSockets {
            override fun factoryFor(address: InetSocketAddress): SocketFactory = SocketFactory.getDefault()

            override fun isOnLocalNetwork(address: InetAddress): Boolean = address == home
        }
        val links = SocketLanLinks(sockets, identity)

        links.isOnLocalNetwork("10.128.0.57") shouldBe true
        links.isOnLocalNetwork("10.0.0.1") shouldBe false
        links.isOnLocalNetwork("evil.lan") shouldBe false
    }

    @Test
    fun keepingAwakeAndMoreConnectionsArePassedThrough() {
        var awake = 0
        val sockets = object : LanSockets {
            override fun factoryFor(address: InetSocketAddress): SocketFactory = SocketFactory.getDefault()

            override fun <T> keepingAwake(session: () -> T): T {
                awake++
                return session()
            }

            override fun canUseMoreConnections(): Boolean = false
        }
        val links = SocketLanLinks(sockets, identity)

        links.keepingAwake { 42 } shouldBe 42
        awake shouldBe 1
        links.canUseMoreConnections() shouldBe false
    }

    @Test
    fun aHostThatIsNotALiteralFailsWithoutOpeningASocket() {
        var opened = 0
        val sockets = LanSockets { _ ->
            opened++
            SocketFactory.getDefault()
        }
        val links = SocketLanLinks(sockets, identity)

        assertThrows(IOException::class.java) {
            links.connect(HostPort("example.com", 47_420), DeviceId("0".repeat(64)), connectTimeoutMillis = 1_000)
        }
        opened shouldBe 0
    }

    @Test
    fun anUnknownKindOfRouteIsRejected() {
        val sockets = LanSockets { SocketFactory.getDefault() }
        val links = SocketLanLinks(sockets, identity)
        val route = object : LinkRoute {}

        val thrown = assertThrows(IOException::class.java) {
            links.connect(
                HostPort("192.168.1.20", 47_420),
                DeviceId("0".repeat(64)),
                connectTimeoutMillis = 1_000,
                via = route,
            )
        }
        thrown.message.orEmpty() shouldContain "Unknown kind of link"
    }
}
