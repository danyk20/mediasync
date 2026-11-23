/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.IdentityProvider
import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory

/**
 * A direct link this phone joined, as a route: the address of the phone that opened it, and the sockets that reach it.
 */
class SocketRoute(val address: InetAddress, val sockets: SocketFactory) : LinkRoute

/** [LanLinks] over TCP sockets and TLS (transport:lan), on the JVM: Android and desktop. */
class SocketLanLinks(private val sockets: LanSockets, private val identity: IdentityProvider) : LanLinks {
    override fun connect(
        address: HostPort,
        expected: DeviceId,
        connectTimeoutMillis: Int,
        via: LinkRoute?,
    ): PeerConnection {
        val target: InetSocketAddress
        val factory: SocketFactory
        when (via) {
            null -> {
                target = parseAddress(address.toString()) ?: throw IOException("Not a literal address: $address")
                factory = sockets.factoryFor(target)
            }

            is SocketRoute -> {
                target = InetSocketAddress(via.address, address.port)
                factory = via.sockets
            }

            else -> throw IOException("Unknown kind of link: $via")
        }
        return LanClient.connect(
            target,
            identity.identity(),
            expected,
            LanTimeouts(connectMillis = connectTimeoutMillis),
            factory,
        )
    }

    // Re-parses through parseAddress, so a host that is not a literal is refused without a DNS lookup.
    override fun isOnLocalNetwork(host: String): Boolean =
        parseAddress(HostPort(host, 1).toString())?.address?.let(sockets::isOnLocalNetwork) == true

    override fun <T> keepingAwake(session: () -> T): T = sockets.keepingAwake(session)

    override fun canUseMoreConnections(): Boolean = sockets.canUseMoreConnections()
}
