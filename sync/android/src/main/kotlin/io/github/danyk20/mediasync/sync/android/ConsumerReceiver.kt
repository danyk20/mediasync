/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.crypto.IdentityProvider
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.transport.lan.LanServer
import io.github.danyk20.mediasync.transport.lan.PeerTrust
import java.io.Closeable
import java.io.IOException
import java.net.BindException
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
    }

    @Synchronized
    fun stop() {
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

    private suspend fun acceptLoop(server: LanServer) {
        while (scope.isActive) {
            val pending = try {
                server.accept()
            } catch (_: IOException) {
                break // Closed by stop().
            }
            scope.launch(Dispatchers.IO) { serve(pending) }
        }
    }

    private fun serve(pending: LanServer.Pending) {
        val connection = try {
            pending.handshake()
        } catch (_: IOException) {
            return // Not a paired phone (or not MediaSync at all).
        }
        val producer = connection.peerId
        markActivity()
        connection.use {
            val session = ConsumerSession(
                device.local(),
                peers,
                ledger,
                sink,
                gate,
                progress = { progress ->
                    _state.update {
                        it.copy(
                            transfers = it.transfers + (producer to progress),
                            lastActivityMillis = System.currentTimeMillis(),
                        )
                    }
                },
            )
            val result = try {
                session.serve(it)
            } catch (_: IOException) {
                null // Dropped: kept partial files resume next time.
            }
            _state.update { state ->
                state.copy(
                    transfers = state.transfers - producer,
                    lastPaired = (result as? ServeResult.Paired)?.peer ?: state.lastPaired,
                    lastActivityMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun markActivity() = _state.update { it.copy(lastActivityMillis = System.currentTimeMillis()) }

    companion object {
        /** Fixed when possible, so a phone's last known address keeps working. */
        const val PREFERRED_PORT = 47_420
        private const val MAX_NAME = 64
    }
}
