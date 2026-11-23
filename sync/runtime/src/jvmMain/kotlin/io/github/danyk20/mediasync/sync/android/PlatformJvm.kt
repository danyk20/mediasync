/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory

/** This phone's addresses on the local network, or none when it isn't on Wi-Fi or Ethernet. */
fun interface AddressProvider {
    fun localAddresses(): List<InetAddress>

    /** Runs [session] with the Wi-Fi radio kept out of power saving (docs/PLAN.md §7), as a receiving phone. */
    fun <T> keepingAwake(session: () -> T): T = session()
}

/**
 * Opens connections to other phones through the local network. Android may route traffic over mobile data
 * while the Wi-Fi has no internet, but phones on the same Wi-Fi still reach each other through it.
 */
fun interface LanSockets {
    fun factoryFor(address: InetSocketAddress): SocketFactory

    /** Whether [address] is on one of the Wi-Fi or Ethernet networks this phone is on, not behind a router. */
    fun isOnLocalNetwork(address: InetAddress): Boolean = true

    /** Runs [session] with the Wi-Fi radio kept out of power saving (docs/PLAN.md §7), as a sending phone. */
    fun <T> keepingAwake(session: () -> T): T = session()

    /** Whether a sync may open another connection: not on a warm phone, as more mean more radio and CPU work. */
    fun canUseMoreConnections(): Boolean = true
}

/**
 * The addresses worth putting in a pairing QR code: each one makes the code denser and harder to scan. A receiving
 * phone also tells these to its sending phones in every session (`Hello.wifiAddresses`).
 * Home networks route IPv4 reliably, so IPv6 is only used without it, and then only addresses that last:
 * private (ULA) ones first, then global ones, never deprecated site-local (`fec0::`) or link-local ones.
 */
fun pairingAddresses(addresses: List<InetAddress>): List<InetAddress> {
    val usable = addresses.distinct().filterNot { it.isLinkLocalAddress || it.isAnyLocalAddress }
    val ipv4 = usable.filterIsInstance<Inet4Address>()
    if (ipv4.isNotEmpty()) return ipv4
    return usable.filterIsInstance<Inet6Address>()
        .filterNot { it.isSiteLocalAddress }
        .sortedByDescending { (it.address[0].toInt() and ULA_MASK) == ULA_PREFIX }
        .take(MAX_IPV6_ADDRESSES)
}

/** `192.168.1.20:47420` or `[fd00::1]:47420`. */
fun formatAddress(address: InetAddress, port: Int): String {
    val host = address.hostAddress?.substringBefore('%') ?: error("Unresolved address")
    return if (address is Inet6Address) "[$host]:$port" else "$host:$port"
}

/** Parses [formatAddress] output; `null` if malformed. */
fun parseAddress(text: String): InetSocketAddress? {
    val separator = text.lastIndexOf(':').takeIf { it > 0 } ?: return null
    val host = text.substring(0, separator).removePrefix("[").removeSuffix("]")
    val port = text.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..MAX_PORT }
    // Literal addresses only: never trigger a DNS lookup for something read from a QR code.
    val literal = host.isNotEmpty() && host.all { it.isDigit() || it in "abcdefABCDEF.:" }
    if (port == null || !literal) return null
    return runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
}

private const val MAX_PORT = 65_535
private const val MAX_IPV6_ADDRESSES = 2
private const val ULA_MASK = 0xFE // fc00::/7
private const val ULA_PREFIX = 0xFC

/**
 * A direct link this phone joined: the address of the phone that opened it, and the [sockets] that reach it there (a
 * Wi-Fi Aware link is a network of its own).
 */
@Suppress("FunctionName") // A factory that stands in for the constructor, so callers passing an address keep compiling.
fun JoinedLink(host: InetAddress, sockets: SocketFactory = SocketFactory.getDefault()): JoinedLink =
    JoinedLink(host.hostAddress ?: error("Unresolved address"), SocketRoute(host, sockets))
