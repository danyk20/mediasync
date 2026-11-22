/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.sync.android.AddressProvider
import io.github.danyk20.mediasync.sync.android.ServiceDiscovery
import io.github.danyk20.mediasync.sync.android.formatAddress
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a receiver advertises: the same service as the Android app's `NsdServiceDiscovery`, so phones find it. */
data class MdnsService(val type: String, val name: String, val port: Int, val txt: Map<String, String>)

/** A receiver found on the network, resolved: its TXT records, port and addresses (in no particular order). */
class MdnsPeer(val txt: Map<String, String>, val port: Int, val addresses: List<InetAddress>)

/** One multicast-DNS responder bound to one local address; a seam over JmDNS, which can't be faked. */
interface MdnsSession : Closeable {
    /** Announces [service]; may take a second or two (the name is probed first). */
    fun register(service: MdnsService)

    /** Calls [found] for every service of [type] on the network, once resolved; stops when the result is closed. */
    fun browse(type: String, found: (MdnsPeer) -> Unit): Closeable
}

/** Opens a session bound to [address]; throws if it can't (no multicast on that interface, say). */
fun interface MdnsOpener {
    fun open(address: InetAddress): MdnsSession
}

/**
 * [ServiceDiscovery] for a computer: advertises and finds `_mediasync._tcp` like the phones do (same type, name, port
 * and `id` TXT record). A computer has several network interfaces, and a multicast socket answers on only one, so
 * this runs one [MdnsSession] per LAN address of [network] (which already skips VPNs, Docker and the like) and merges
 * what they find. Sessions are opened for each call and closed after, so a changed network is picked up next time.
 * IPv6 addresses only get a session when the computer has no IPv4 one: mDNS over IPv4 is what phones answer on.
 * Closing never blocks for more than [closeTimeoutMillis]: JmDNS can hang there.
 */
class DesktopServiceDiscovery(
    private val network: AddressProvider,
    private val opener: MdnsOpener = JmdnsOpener,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
) : ServiceDiscovery {

    override fun advertise(port: Int, serviceId: String): Closeable {
        val service = MdnsService(SERVICE_TYPE, SERVICE_NAME, port, mapOf(ID_ATTRIBUTE to serviceId))
        val executor = Executors.newSingleThreadExecutor(daemonThreads("mediasync-advertise"))
        val sessions = CopyOnWriteArrayList<MdnsSession>()
        // Probing blocks for seconds, and the caller needs to go on: announce in the background.
        executor.execute {
            for (address in sessionAddresses()) {
                runCatching { opener.open(address) }.onSuccess { session ->
                    sessions += session
                    runCatching { session.register(service) }
                }
            }
        }
        return Closeable {
            executor.shutdown()
            // Wait for an announcement still being made, so its session is in the list and gets closed too.
            runCatching { executor.awaitTermination(closeTimeoutMillis, TimeUnit.MILLISECONDS) }
            closeAll(sessions.toList(), closeTimeoutMillis)
            sessions.clear()
        }
    }

    override suspend fun find(serviceId: String, timeoutMillis: Long): String? {
        val result = CompletableDeferred<String>()
        val sessions = CopyOnWriteArrayList<MdnsSession>()
        val browses = CopyOnWriteArrayList<Closeable>()
        try {
            return withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    for (address in sessionAddresses()) {
                        val session = runCatching { opener.open(address) }.getOrNull() ?: continue
                        sessions += session
                        browses += session.browse(SERVICE_TYPE) { peer ->
                            val target = peer.takeIf { it.txt[ID_ATTRIBUTE] == serviceId }?.let(::addressOf)
                            if (target != null) result.complete(target)
                        }
                    }
                }
                result.await()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                browses.forEach { runCatching { it.close() } }
                closeAll(sessions.toList(), closeTimeoutMillis)
            }
        }
    }

    private fun sessionAddresses(): List<InetAddress> {
        val all = network.localAddresses()
        val ipv4 = all.filterIsInstance<Inet4Address>()
        return ipv4.ifEmpty { all }.distinct()
    }

    /** The address to dial: IPv4 if the peer has one, never a link-local address (they need a scope). */
    private fun addressOf(peer: MdnsPeer): String? {
        val usable = peer.addresses.filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
        val best = usable.firstOrNull { it is Inet4Address } ?: usable.firstOrNull() ?: return null
        return formatAddress(best, peer.port)
    }

    internal companion object {
        const val SERVICE_TYPE = "_mediasync._tcp.local."
        const val SERVICE_NAME = "MediaSync"
        const val ID_ATTRIBUTE = "id"
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 3_000L

        fun daemonThreads(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }

        /** Closes every session in parallel on daemon threads and gives up on the ones that take over [timeout]. */
        fun closeAll(sessions: List<MdnsSession>, timeout: Long) {
            if (sessions.isEmpty()) return
            val executor = Executors.newFixedThreadPool(sessions.size, daemonThreads("mediasync-mdns-close"))
            try {
                val pending = sessions.map { session -> executor.submit { runCatching { session.close() } } }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
                for (task in pending) {
                    val left = deadline - System.nanoTime()
                    runCatching { task.get(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) }
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
