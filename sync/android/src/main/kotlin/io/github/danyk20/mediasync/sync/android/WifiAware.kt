/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.HandlerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turbo transfer's direct links over Wi-Fi Aware (docs/PLAN.md §8.3), for phones that both have it: faster to set
 * up than Wi-Fi Direct, and the normal Wi-Fi stays up beside it.
 *
 * The receiving phone publishes a service under a fresh random name. The sending phone, told the name and a
 * passphrase in an encrypted session, looks for it, says hello, and both ask Android for a data path protected by the
 * passphrase: a network of its own, with link-local IPv6 addresses. Emulators have no Wi-Fi Aware, so this runs on
 * real phones only; Wi-Fi Direct stays the fallback.
 */
@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest without Wi-Fi Aware; the radio on real phones only")
@SuppressLint("MissingPermission") // Each call that needs a permission runs after availability() found it granted.
@Singleton
class WifiAware @Inject constructor(@ApplicationContext private val context: Context) : DirectLinks {
    private val manager: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Answers arrive here, not on the main thread, which may be the one waiting for them. */
    private val callbacks: Handler by lazy { Handler(HandlerThread("mediasync-wifi-aware").apply { start() }.looper) }

    /** As a receiving phone: the service published now. */
    @Volatile
    private var published: Opened? = null

    /** As a sending phone: the link joined now. */
    @Volatile
    private var joined: Opened? = null

    /** A Wi-Fi Aware attachment, its discovery session, and the data paths asked for over it. */
    private inner class Opened(val name: String, val session: WifiAwareSession) {
        @Volatile
        var discovery: DiscoverySession? = null
        val dataPaths = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()

        fun close() {
            dataPaths.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
            discovery?.close()
            session.close()
        }
    }

    override fun availability(): TurboAvailability = when {
        manager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) ->
            TurboAvailability.UNSUPPORTED

        context.checkSelfPermission(TurboPermissions.needed) != PackageManager.PERMISSION_GRANTED ->
            TurboAvailability.NOT_ALLOWED

        // Off with the Wi-Fi, with location before Android 13, and on some phones while Wi-Fi Direct is up.
        !manager.isAvailable -> TurboAvailability.UNSUPPORTED

        else -> TurboAvailability.AVAILABLE
    }

    override fun awareAvailable(): Boolean = availability() == TurboAvailability.AVAILABLE

    override fun open(port: Int, credentials: Pair<String, String>, kind: LinkKind): TurboAnswer {
        if (kind != LinkKind.WIFI_AWARE || !awareAvailable()) return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        return runBlocking { withTimeoutOrNull(OPEN_TIMEOUT_MILLIS) { publish(port, credentials) } }
            ?: TurboAnswer.Unavailable(TurboUnavailable.FAILED).also {
                Diagnostics.log(Area.RECEIVE, "Turbo: Wi-Fi Aware wasn't ready within ${since(0, OPEN_TIMEOUT_MILLIS)}")
                close(credentials.first)
            }
    }

    private suspend fun publish(port: Int, credentials: Pair<String, String>): TurboAnswer {
        val (name, passphrase) = credentials
        val session = attach(Area.RECEIVE) ?: return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        val opened = Opened(name, session).also { published = it }
        val started = CompletableDeferred<Boolean>()
        val greeted = mutableSetOf<PeerHandle>()
        val config = PublishConfig.Builder().setServiceName(name).build()
        session.publish(
            config,
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    opened.discovery = session
                    started.complete(true)
                }

                override fun onSessionConfigFailed() {
                    Diagnostics.log(Area.RECEIVE, "Turbo: Wi-Fi Aware couldn't publish")
                    started.complete(false)
                }

                // A sending phone found the service and said hello: its data path, if it has the passphrase.
                override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                    val publishing = opened.discovery ?: return
                    if (!greeted.add(peer)) return
                    val specifier = WifiAwareNetworkSpecifier.Builder(publishing, peer)
                        .setPskPassphrase(passphrase)
                        .setPort(port)
                        .setTransportProtocol(TCP)
                        .build()
                    opened.dataPaths += requestDataPath(specifier, object : ConnectivityManager.NetworkCallback() {})
                }
            },
            callbacks,
        )
        if (!started.await()) {
            close(name)
            return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        }
        return TurboAnswer.Ready(DirectLink(name, passphrase, host = "", port = port, kind = LinkKind.WIFI_AWARE))
    }

    override fun close(networkName: String) {
        val opened = published?.takeIf { it.name == networkName } ?: return
        published = null
        opened.close()
    }

    override suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink? {
        if (link.kind != LinkKind.WIFI_AWARE || !awareAvailable()) return null
        return withTimeoutOrNull(timeoutMillis) { subscribe(link) }
    }

    private suspend fun subscribe(link: DirectLink): JoinedLink? {
        val session = attach(Area.SEND) ?: return null
        val opened = Opened(link.networkName, session).also { joined = it }
        val (subscribing, peer) = greet(opened) ?: return null
        val specifier = WifiAwareNetworkSpecifier.Builder(subscribing, peer).setPskPassphrase(link.passphrase).build()
        val reached = CompletableDeferred<JoinedLink>()
        opened.dataPaths += requestDataPath(specifier, PeerAddress(reached))
        return reached.await()
    }

    /** Finds the receiving phone's service and says hello, which lets it accept a data path from this phone. */
    private suspend fun greet(opened: Opened): Pair<DiscoverySession, PeerHandle>? {
        val found = CompletableDeferred<PeerHandle?>()
        val greeted = CompletableDeferred<Boolean>()
        val config = SubscribeConfig.Builder().setServiceName(opened.name).build()
        opened.session.subscribe(
            config,
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    opened.discovery = session
                }

                override fun onSessionConfigFailed() {
                    Diagnostics.log(Area.SEND, "Turbo: Wi-Fi Aware couldn't look for the receiving phone")
                    found.complete(null)
                }

                override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray, filter: List<ByteArray>) {
                    found.complete(peer)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    greeted.complete(true)
                }

                override fun onMessageSendFailed(messageId: Int) {
                    Diagnostics.log(Area.SEND, "Turbo: Wi-Fi Aware couldn't reach the receiving phone")
                    greeted.complete(false)
                }
            },
            callbacks,
        )
        val peer = found.await()
        val subscribing = opened.discovery
        if (peer == null || subscribing == null) return null
        subscribing.sendMessage(peer, HELLO_ID, HELLO)
        return (subscribing to peer).takeIf { greeted.await() }
    }

    override fun leave() {
        val opened = joined ?: return
        joined = null
        opened.close()
    }

    /** Asks Android for the data path [specifier] describes; it lasts until [callback] is unregistered. */
    private fun requestDataPath(
        specifier: WifiAwareNetworkSpecifier,
        callback: ConnectivityManager.NetworkCallback,
    ): ConnectivityManager.NetworkCallback {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        connectivity.requestNetwork(request, callback, callbacks)
        return callback
    }

    /**
     * The receiving phone's address on the data path, and sockets bound to it. Android tells the address with the
     * network's capabilities, and the interface, which a link-local address needs, with its link properties.
     */
    private class PeerAddress(private val reached: CompletableDeferred<JoinedLink>) :
        ConnectivityManager.NetworkCallback() {
        @Volatile
        private var peer: Inet6Address? = null

        @Volatile
        private var interfaceName: String? = null

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            (capabilities.transportInfo as? WifiAwareNetworkInfo)?.peerIpv6Addr?.let { peer = it }
            completeOver(network)
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            interfaceName = properties.interfaceName
            completeOver(network)
        }

        /** Once both are known, whichever came last. */
        private fun completeOver(network: Network) {
            val address = peer ?: return
            val name = interfaceName ?: return
            reached.complete(JoinedLink(address.scopedTo(name), network.socketFactory))
        }

        private fun Inet6Address.scopedTo(name: String): Inet6Address = if (scopedInterface != null || scopeId != 0) {
            this
        } else {
            Inet6Address.getByAddress(null, address, NetworkInterface.getByName(name))
        }
    }

    private suspend fun attach(area: Area): WifiAwareSession? = suspendCancellableCoroutine { continuation ->
        checkNotNull(manager).attach(
            object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }

                override fun onAttachFailed() {
                    Diagnostics.log(area, "Turbo: Wi-Fi Aware couldn't start")
                    if (continuation.isActive) continuation.resume(null)
                }
            },
            callbacks,
        )
    }

    private companion object {
        const val OPEN_TIMEOUT_MILLIS = 10_000L
        const val TCP = 6 // IPPROTO_TCP, for the data path's port.
        const val HELLO_ID = 1
        val HELLO = "mediasync".toByteArray()
    }
}
