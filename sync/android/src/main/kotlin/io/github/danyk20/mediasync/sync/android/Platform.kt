/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import java.io.Closeable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory
import okio.ByteString.Companion.encodeUtf8

// Small seams around Android services, so the sync runtime can be tested on the JVM.

/** How this phone introduces itself to others, and how its user set it up. */
fun interface DeviceInfo {
    fun local(): LocalDevice

    /** As a sending phone: the details it sends (docs/PLAN.md §6.3). */
    fun photoDetails(): PhotoDetails = PhotoDetails.Default
}

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

    /** Runs [session] with the Wi-Fi radio kept out of power saving (docs/PLAN.md §7), as a sending phone. */
    fun <T> keepingAwake(session: () -> T): T = session()

    /** Whether a sync may open another connection: not on a warm phone, as more mean more radio and CPU work. */
    fun canUseMoreConnections(): Boolean = true
}

/** Why background work waits for better conditions (docs/PLAN.md §10.5). */
enum class PauseReason {
    /** The phone is hot: transfers would heat it further. */
    HOT,

    /** Below 20 %, even while charging: slow chargers exist. */
    LOW_BATTERY,
}

/** Whether background work should wait; `null` to go ahead. User-started syncs don't ask. */
fun interface DeviceConditions {
    fun pauseReason(): PauseReason?
}

/** The *Photo details* a sending phone sends (docs/PLAN.md §6.3), as the user set them. */
fun interface PhotoDetailsSource {
    fun current(): PhotoDetails
}

/** The receiving phone's sync schedule as the user set it; told to every sending phone in each session. */
fun interface ScheduleSource {
    fun current(): SyncSchedule
}

/** Advertising and finding MediaSync receivers on the local network (mDNS / DNS-SD). */
interface ServiceDiscovery {
    /** Advertises this receiver until the returned handle is closed. */
    fun advertise(port: Int, serviceId: String): Closeable

    /** The address (`host:port`) of the receiver advertising [serviceId], or `null` after [timeoutMillis]. */
    suspend fun find(serviceId: String, timeoutMillis: Long): String?
}

/**
 * What a receiver advertises on the network instead of its device id: a hash only someone who already knows
 * the id (a paired phone) can match, so the advertisement doesn't reveal which device it is.
 */
fun serviceIdOf(device: DeviceId): String =
    "mediasync-nsd-v1:${device.hex}".encodeUtf8().sha256().hex().take(SERVICE_ID_LENGTH)

/**
 * The addresses worth putting in a pairing QR code: each one makes the code denser and harder to scan.
 * Home networks route IPv4 reliably, so IPv6 is only used without it, and then only addresses that last:
 * private (ULA) ones first, then global ones, never deprecated site-local (`fec0::`) or link-local ones.
 */
internal fun pairingAddresses(addresses: List<InetAddress>): List<InetAddress> {
    val usable = addresses.distinct().filterNot { it.isLinkLocalAddress || it.isAnyLocalAddress }
    val ipv4 = usable.filterIsInstance<Inet4Address>()
    if (ipv4.isNotEmpty()) return ipv4
    return usable.filterIsInstance<Inet6Address>()
        .filterNot { it.isSiteLocalAddress }
        .sortedByDescending { (it.address[0].toInt() and ULA_MASK) == ULA_PREFIX }
        .take(MAX_IPV6_ADDRESSES)
}

/** `192.168.1.20:47420` or `[fd00::1]:47420`. */
internal fun formatAddress(address: InetAddress, port: Int): String {
    val host = address.hostAddress?.substringBefore('%') ?: error("Unresolved address")
    return if (address is Inet6Address) "[$host]:$port" else "$host:$port"
}

/** Parses [formatAddress] output; `null` if malformed. */
internal fun parseAddress(text: String): InetSocketAddress? {
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
private const val SERVICE_ID_LENGTH = 16
