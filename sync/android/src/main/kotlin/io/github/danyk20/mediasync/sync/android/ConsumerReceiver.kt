/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.crypto.IdentityProvider
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.SpaceBudget
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.LanServer
import io.github.danyk20.mediasync.transport.lan.PeerTrust
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the consumer's home screen shows. */
data class ReceiverState(
    val listening: Boolean = false,
    val port: Int? = null,
    /** Transfers in progress, by producer. */
    val transfers: Map<DeviceId, TransferProgress> = emptyMap(),
    /** The phone paired most recently in this run, for the pairing screen's success message. */
    val lastPaired: PairedPeer? = null,
    /** When a phone last connected or sent something; a receive window closes after a quiet spell. */
    val lastActivityMillis: Long? = null,
    /** Photos and videos the last sync offered that didn't fit in the free space. */
    val waitingForSpace: Int = 0,
)

/**
 * The receiving side: listens for paired phones on the local network and stores what they send
 * (docs/PLAN.md §5, §8.2).
 *
 * It listens while something needs it ([hold]): an open screen, or a scheduled receive window running in the
 * background while charging (docs/PLAN.md §10.1). In every session it tells the sending phone its schedule.
 */
@Singleton
class ConsumerReceiver internal constructor(
    private val identity: IdentityProvider,
    private val peers: PeerStore,
    private val ledger: ReceivedLedger,
    private val sink: MediaSink,
    private val device: DeviceInfo,
    private val addresses: AddressProvider,
    private val discovery: ServiceDiscovery,
    private val scope: CoroutineScope,
    private val preferredPort: Int,
) {
    @Inject
    constructor(
        identity: IdentityProvider,
        peers: PeerStore,
        ledger: ReceivedLedger,
        sink: MediaSink,
        device: DeviceInfo,
        addresses: AddressProvider,
        discovery: ServiceDiscovery,
        @ApplicationScope scope: CoroutineScope,
    ) : this(identity, peers, ledger, sink, device, addresses, discovery, scope, PREFERRED_PORT)

    private val window = PairingWindow()
    private val approvals = PairingApprovals()
    private val gate = pairingGate(window, approvals)
    private val _state = MutableStateFlow(ReceiverState())
    private var server: LanServer? = null
    private var advertisement: Closeable? = null
    private var acceptJob: Job? = null
    private val holders = mutableSetOf<String>()

    /** One budget for every session at once: a sender's parallel connections, or several senders. */
    private val space = SpaceBudget(sink)

    /** Progress of each session running now, by session; a sender may have several (docs/PLAN.md §7). */
    private val sessions = ConcurrentHashMap<Long, Pair<DeviceId, TransferProgress>>()
    private val nextSession = AtomicLong()

    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    /** A pairing request the user has to answer. */
    val pendingApproval = approvals.pending

    /** Keeps listening until every [holder] (e.g. `"screen"`, `"window"`) has called [release]. */
    @Synchronized
    fun hold(holder: String) {
        holders += holder
        start()
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) stop()
    }

    /** Starts listening, if it isn't already. */
    @Synchronized
    fun start() {
        if (acceptJob?.isActive == true) return
        // TLS authenticates every phone (it proves it holds its key). *Authorization* happens in the session:
        // an unpaired phone may only present a pairing token, and is otherwise told it isn't paired, so its
        // user sees why, instead of an anonymous TLS failure.
        val trust = PeerTrust { true }
        val server = try {
            LanServer(identity.identity(), trust, preferredPort)
        } catch (_: BindException) {
            LanServer(identity.identity(), trust) // Preferred port taken: any free port; mDNS still finds us.
        }
        this.server = server
        advertisement =
            runCatching { discovery.advertise(server.port, serviceIdOf(identity.identity().id)) }.getOrNull()
        _state.update { it.copy(listening = true, port = server.port) }
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        Diagnostics.log(Area.RECEIVE, "Listening on port ${server.port} for ${holders.joinToString()}")
    }

    @Synchronized
    fun stop() {
        if (server != null) Diagnostics.log(Area.RECEIVE, "Stopped listening")
        acceptJob?.cancel()
        server?.close()
        advertisement?.close()
        server = null
        advertisement = null
        _state.update { it.copy(listening = false, port = null, transfers = emptyMap()) }
    }

    /**
     * Opens a pairing invitation for the QR code. `null` when this phone isn't listening or has no local
     * address (not on Wi-Fi).
     */
    fun openPairing(): PairingLink? {
        val port = _state.value.port ?: return null
        val local = pairingAddresses(addresses.localAddresses()).map { formatAddress(it, port) }
        if (local.isEmpty()) return null
        _state.update { it.copy(lastPaired = null) }
        return PairingLink(identity.identity().id, window.open(), local, device.local().name.take(MAX_NAME))
    }

    fun closePairing() = window.close()

    /** How much more this phone can receive; 0 when its storage is full (docs/PLAN.md §2.2). */
    fun freeBytes(): Long = sink.availableBytes()

    private suspend fun acceptLoop(server: LanServer) {
        while (scope.isActive) {
            val pending = try {
                server.accept()
            } catch (e: IOException) {
                if (scope.isActive && this.server === server) Diagnostics.log(Area.RECEIVE, "Stopped accepting", e)
                break // Closed by stop().
            }
            scope.launch(Dispatchers.IO) { serve(pending) }
        }
    }

    private fun serve(pending: LanServer.Pending) {
        val connection = try {
            pending.handshake()
        } catch (e: IOException) {
            Diagnostics.log(Area.RECEIVE, "A connection failed its handshake", e)
            return // Not a paired phone (or not MediaSync at all).
        }
        val producer = connection.peerId
        val started = System.currentTimeMillis()
        Diagnostics.log(Area.RECEIVE, "Session from ${producer.short()} at ${connection.remoteAddress}")
        markActivity()
        addresses.keepingAwake { receive(producer, connection, started) }
    }

    /** One session with [producer], from its first message to its end. */
    private fun receive(producer: DeviceId, connection: PeerConnection, started: Long) {
        val id = nextSession.incrementAndGet()
        connection.use {
            val session = ConsumerSession(
                device.local(),
                peers,
                ledger,
                sink,
                gate,
                progress = { progress ->
                    sessions[id] = producer to progress
                    _state.update { it.copy(transfers = transfers(), lastActivityMillis = System.currentTimeMillis()) }
                },
                space = space,
            )
            val result = try {
                session.serve(it).also { result ->
                    Diagnostics.log(
                        Area.RECEIVE,
                        "Session from ${producer.short()} ended in ${since(started)}: ${result.describe()}",
                    )
                }
            } catch (e: IOException) {
                val progress = _state.value.transfers[producer]
                Diagnostics.log(
                    Area.RECEIVE,
                    "Session from ${producer.short()} dropped after ${since(started)} at ${progress.describe()}; " +
                        "free ${bytes(sink.availableBytes())}",
                    e,
                )
                null // Dropped: kept partial files resume next time.
            }
            sessions.remove(id)
            _state.update { state ->
                state.copy(
                    transfers = transfers(),
                    lastPaired = (result as? ServeResult.Paired)?.peer ?: state.lastPaired,
                    lastActivityMillis = System.currentTimeMillis(),
                    waitingForSpace = (result as? ServeResult.Synced)?.waitingForSpace ?: state.waitingForSpace,
                )
            }
        }
    }

    /** Each sender's sessions, added up. */
    private fun transfers(): Map<DeviceId, TransferProgress> = sessions.values.groupBy({ it.first }, { it.second })
        .mapValues { (_, parts) ->
            parts.reduce { a, b ->
                TransferProgress(
                    itemsDone = a.itemsDone + b.itemsDone,
                    itemsTotal = a.itemsTotal + b.itemsTotal,
                    bytesDone = a.bytesDone + b.bytesDone,
                    bytesTotal = a.bytesTotal + b.bytesTotal,
                    currentItem = b.currentItem ?: a.currentItem,
                )
            }
        }

    private fun markActivity() = _state.update { it.copy(lastActivityMillis = System.currentTimeMillis()) }

    companion object {
        /** Fixed when possible, so a phone's last known address keeps working. */
        const val PREFERRED_PORT = 47_420
        private const val MAX_NAME = 64
    }
}
