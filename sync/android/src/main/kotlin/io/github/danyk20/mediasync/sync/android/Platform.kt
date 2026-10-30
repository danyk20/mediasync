/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import javax.inject.Inject
import javax.net.SocketFactory
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

// Small seams around Android services, so the sync runtime can be tested on the JVM.

/** How this phone introduces itself to others, and how its user set it up. */
fun interface DeviceInfo {
    fun local(): LocalDevice

    /** As a sending phone: the details it sends (docs/PLAN.md §6.3). */
    fun photoDetails(): PhotoDetails = PhotoDetails.Default

    /** As a sending phone: in which order it sends what's waiting. */
    fun sendingOrder(): SendingOrder = SendingOrder.Default

    /**
     * As a sending phone: whether videos also go through the relay; otherwise they wait for Wi-Fi or a direct
     * connection (docs/PLAN.md §17, relay saver).
     */
    fun videosOverRelay(): Boolean = false
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

/**
 * How this phone reaches other phones: its local network, mDNS on it, direct links, and the internet
 * (docs/PLAN.md §8).
 */
class Connectivity @Inject constructor(
    val addresses: AddressProvider,
    val sockets: LanSockets,
    val discovery: ServiceDiscovery,
    val directLinks: DirectLinks,
    val internet: InternetLinks = InternetLinks.None,
)

/**
 * Internet sync (docs/PLAN.md §8.4): connections between the paired phones through the receiving phone's
 * rendezvous Worker, when they share no network.
 */
interface InternetLinks {
    /**
     * As a sending phone: whether it may sync over the internet now: over Wi-Fi, or over mobile data if allowed and
     * this month's limit isn't used up.
     */
    fun allowed(): Boolean

    /**
     * As a sending phone on mobile data: how much more it may send over it this month ([Long.MAX_VALUE] without a
     * limit); `null` when it isn't on mobile data.
     */
    fun mobileDataLeft(): Long? = null

    /** As a sending phone: [bytes] went over mobile data. */
    fun countMobileData(bytes: Long) = Unit

    /**
     * As a sending phone: a connection to [consumer] through its Worker at [worker], with their [pairSecret].
     * Blocks.
     *
     * @throws IOException if there's no way to it now; the message says why.
     */
    fun connect(consumer: DeviceId, worker: String, pairSecret: ByteString): PeerConnection

    /**
     * As a receiving phone: answers the sending phones in [pairs] (each with its pair secret) that connect
     * through the Worker at [worker]. Each connection goes to [serve] on a thread of its own, until the
     * returned handle is closed.
     */
    fun listen(worker: String, pairs: Map<DeviceId, ByteString>, serve: (PeerConnection) -> Unit): Closeable

    /**
     * As a receiving phone: this month's relay usage at its Worker at [worker] (docs/PLAN.md §8.5), asked as the
     * pair with [producer]. Blocks.
     *
     * @throws IOException if the Worker can't be asked now.
     */
    fun relayUsage(worker: String, producer: DeviceId): RelayUsage

    /**
     * As a receiving phone: has its Worker at [worker] send it a wake-up check, as the pair with [producer], the way a
     * sending phone's wake-up goes (docs/PLAN.md §10.4). Blocks until FCM took it.
     *
     * @throws IOException if it couldn't; a `RendezvousException` carries the Worker's reason.
     */
    fun wakeCheck(worker: String, producer: DeviceId): Unit = throw IOException("No internet sync")

    /** Without internet sync. */
    object None : InternetLinks {
        override fun allowed() = false

        override fun connect(consumer: DeviceId, worker: String, pairSecret: ByteString): PeerConnection =
            throw IOException("No internet sync on this phone")

        override fun listen(worker: String, pairs: Map<DeviceId, ByteString>, serve: (PeerConnection) -> Unit) =
            Closeable {}

        override fun relayUsage(worker: String, producer: DeviceId): RelayUsage =
            throw IOException("No internet sync on this phone")
    }
}

/**
 * The relay allowance this month (docs/PLAN.md §8.5): [usedBytes] of the [freeBytes] Cloudflare gives for free; the
 * relay stops at [limitBytes] (a margin below), and is [available] until then. It starts over at [resetsAt].
 */
data class RelayUsage(
    val usedBytes: Long,
    val limitBytes: Long,
    val freeBytes: Long,
    val available: Boolean,
    val resetsAt: Instant?,
)

/** Whether this phone can use Turbo transfer (docs/PLAN.md §8.3) now. */
enum class TurboAvailability {
    AVAILABLE,

    /** It has no Wi-Fi Direct. */
    UNSUPPORTED,

    /** The user hasn't allowed MediaSync to connect to nearby devices (location, before Android 13). */
    NOT_ALLOWED,

    /** Before Android 13, Wi-Fi Direct also needs location to be on. */
    LOCATION_OFF,
}

/**
 * Direct links between the two phones, for Turbo transfer (docs/PLAN.md §8.3): Wi-Fi Direct, or Wi-Fi Aware where
 * both phones have it. The receiving phone opens a fresh one for each transfer, and the sending phone joins it with
 * what it was told in an encrypted session.
 */
interface DirectLinks {
    fun availability(): TurboAvailability

    /** Whether this phone can use a Wi-Fi Aware link now: it has Wi-Fi Aware, it's on, and it's allowed. */
    fun awareAvailable(): Boolean = false

    /**
     * As a receiving phone: opens a link of [kind] that sending phones join with [credentials] (network or service
     * name, and passphrase), listening on it at [port]. Blocks.
     */
    fun open(
        port: Int,
        credentials: Pair<String, String> = DirectLink.credentials(),
        kind: LinkKind = LinkKind.WIFI_DIRECT,
    ): TurboAnswer

    /** As a receiving phone: removes the link named [networkName], if it's still up. */
    fun close(networkName: String)

    /** As a sending phone: joins [link]; how to reach the phone that opened it, or `null` if not joined in time. */
    suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink?

    /** As a sending phone: leaves the link it joined. */
    fun leave()

    /** For phones without Wi-Fi Direct, and for tests. */
    object None : DirectLinks {
        override fun availability() = TurboAvailability.UNSUPPORTED

        override fun open(port: Int, credentials: Pair<String, String>, kind: LinkKind): TurboAnswer =
            TurboAnswer.Unavailable(TurboUnavailable.UNSUPPORTED)

        override fun close(networkName: String) = Unit

        override suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink? = null

        override fun leave() = Unit
    }
}

/**
 * A direct link this phone joined: the address of the phone that opened it, and the [sockets] that reach it there (a
 * Wi-Fi Aware link is a network of its own).
 */
data class JoinedLink(val host: InetAddress, val sockets: SocketFactory = SocketFactory.getDefault())

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

    /** Below 30 % and not charging: Turbo transfer (docs/PLAN.md §8.3) warns that it uses more power. */
    fun lowForTurbo(): Boolean = false

    /** On a charger, whether or not the battery is charging. */
    fun pluggedIn(): Boolean = true

    /**
     * On a charger, but the battery held below full: Samsung's *Protect battery* stops at 85 %, adaptive charging
     * waits. Android's job scheduler counts that as charging, and WorkManager doesn't (docs/PLAN.md §10.1).
     */
    fun chargingHeld(): Boolean = false
}

/** The *Photo details* a sending phone sends (docs/PLAN.md §6.3), as the user set them. */
fun interface PhotoDetailsSource {
    fun current(): PhotoDetails
}

/** The order a sending phone sends in, as the user set it. */
fun interface SendingOrderSource {
    fun current(): SendingOrder
}

/** As a sending phone: whether videos also go through the relay (PLAN §17, relay saver), as the user set it. */
fun interface RelayVideosSource {
    fun allowed(): Boolean
}

/** As a sending phone: whether internet sync (PLAN §8.4) may also use mobile data, and how much, as the user set it. */
interface MobileDataSource {
    fun allowed(): Boolean

    /** How much it may send over mobile data a month; `null`: no limit. */
    fun capBytes(): Long? = null
}

/** The receiving phone's rendezvous Worker for internet sync (PLAN §8.4), as set up; `null` while it has none. */
fun interface RendezvousSource {
    fun current(): String?
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
