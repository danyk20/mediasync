/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.sync.android.AddressProvider
import io.github.danyk20.mediasync.sync.android.LanSockets
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import javax.net.SocketFactory

/** One network interface as [DesktopLocalNetwork] sees it; a seam over [NetworkInterface], which can't be faked. */
interface LocalInterface {
    val name: String
    val displayName: String
    val isUp: Boolean
    val isLoopback: Boolean
    val isPointToPoint: Boolean

    /** A sub-interface (`eth0:1`): its addresses are also on its parent. */
    val isVirtual: Boolean

    /** Each address with the length of its subnet prefix. */
    val addresses: List<Pair<InetAddress, Int>>

    companion object {
        /** The interfaces of this computer now; none if the system can't list them. */
        fun system(): List<LocalInterface> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map(::SystemInterface)
        } catch (_: SocketException) {
            emptyList()
        }
    }
}

private class SystemInterface(private val nic: NetworkInterface) : LocalInterface {
    override val name: String = nic.name
    override val displayName: String = nic.displayName ?: nic.name
    override val isUp = try {
        nic.isUp
    } catch (_: SocketException) {
        false
    }
    override val isLoopback = try {
        nic.isLoopback
    } catch (_: SocketException) {
        false
    }
    override val isPointToPoint = try {
        nic.isPointToPoint
    } catch (_: SocketException) {
        false
    }
    override val isVirtual = nic.isVirtual
    override val addresses = nic.interfaceAddresses.map { it.address to it.networkPrefixLength.toInt() }
}

/**
 * The computer's addresses on the local network (Ethernet and Wi-Fi), and plain sockets to reach other devices on it.
 * Unlike a phone, a computer has no network to bind to: the operating system routes by subnet. Skips interfaces that
 * are down, loopback, point-to-point (VPNs), virtual or known to be tunnels and virtual switches (Docker, VMs,
 * AirDrop's `awdl`), and addresses that are loopback, link-local or multicast. IPv4 comes first.
 */
class DesktopLocalNetwork(private val interfaces: () -> List<LocalInterface> = LocalInterface::system) :
    AddressProvider,
    LanSockets {

    override fun localAddresses(): List<InetAddress> =
        usable().flatMap { it.addresses.map { (address, _) -> address } }.filter(::worthAdvertising)
            .sortedBy { if (it is Inet4Address) 0 else 1 }.distinct()

    override fun factoryFor(address: InetSocketAddress): SocketFactory = SocketFactory.getDefault()

    /** [address] is in the subnet of one of this computer's usable interfaces. */
    override fun isOnLocalNetwork(address: InetAddress): Boolean = usable().any { nic ->
        nic.addresses.any { (own, prefix) -> worthAdvertising(own) && sameSubnet(own, address, prefix) }
    }

    // No Wi-Fi power saving to hold off on a computer.
    override fun <T> keepingAwake(session: () -> T): T = session()

    private fun usable(): List<LocalInterface> = interfaces().filter { nic ->
        nic.isUp && !nic.isLoopback && !nic.isPointToPoint && !nic.isVirtual && !isVirtualName(nic)
    }

    private fun worthAdvertising(address: InetAddress) = !(
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress ||
            address.isMulticastAddress
        )

    internal companion object {
        /** Name prefixes of tunnels, bridges and virtual switches (macOS, Linux). */
        val VIRTUAL_PREFIXES = listOf(
            "utun", "awdl", "llw", "bridge", "gif", "stf", "anpi", "ap", "docker", "veth", "br-", "virbr", "vmnet",
            "vboxnet", "tailscale", "zt", "tun", "tap", "wg", "lo",
        )

        /** Fragments of the display names Windows gives virtual adapters. */
        val VIRTUAL_DISPLAY_NAMES =
            listOf("virtual", "vmware", "hyper-v", "vpn", "loopback", "tap-", "wireguard", "tunnel")

        fun isVirtualName(nic: LocalInterface): Boolean {
            val name = nic.name.lowercase()
            val display = nic.displayName.lowercase()
            return VIRTUAL_PREFIXES.any { name.startsWith(it) } || VIRTUAL_DISPLAY_NAMES.any { it in display }
        }

        private const val HIGH_BIT = 0x80

        fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
            val x = a.address
            val y = b.address
            if (x.size != y.size) return false
            val bits = prefixLength.coerceIn(0, x.size * Byte.SIZE_BITS)
            for (i in 0 until bits) {
                val mask = HIGH_BIT shr (i % Byte.SIZE_BITS)
                if ((x[i / Byte.SIZE_BITS].toInt() and mask) != (y[i / Byte.SIZE_BITS].toInt() and mask)) return false
            }
            return true
        }
    }
}
