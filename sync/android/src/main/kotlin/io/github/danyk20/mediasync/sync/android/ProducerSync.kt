/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.crypto.IdentityProvider
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.metadata.isWithheld
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.sync.engine.IncompatiblePeerException
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.PairingClient
import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.github.danyk20.mediasync.sync.engine.PeerEndedSessionException
import io.github.danyk20.mediasync.sync.engine.ProducerSession
import io.github.danyk20.mediasync.sync.engine.SentLedger
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a pairing or sync attempt didn't work. */
enum class SyncFailure {
    /** No receiving phone has been paired yet. */
    NOT_PAIRED,

    /** The receiving phone wasn't found: not on the same Wi-Fi, or MediaSync isn't running there. */
    UNREACHABLE,

    /** The connection dropped mid-transfer; what was sent is kept, and the rest resumes next time. */
    CONNECTION_LOST,

    /** The receiving phone no longer recognises this one (it was unpaired there). */
    NOT_ALLOWED,

    /** The other phone runs an incompatible MediaSync version. */
    INCOMPATIBLE,
}

/** What the producer's home screen shows. */
sealed interface ProducerState {
    data class Idle(val lastResult: SyncResult? = null) : ProducerState

    data object Connecting : ProducerState

    data class Syncing(val progress: TransferProgress?) : ProducerState

    data class Failed(val reason: SyncFailure) : ProducerState
}

/**
 * What the consumer doesn't have yet: [toSend], and [withheld] by the *Photo details* setting, whose details can't
 * be removed (RAW photos under *Remove all*, formats MediaSync can't read).
 */
data class Pending(val toSend: Int = 0, val withheld: Int = 0)

/** The outcome of scanning a pairing QR code. */
sealed interface PairingResult {
    data class Paired(val consumerName: String) : PairingResult

    data class Refused(val decision: PairingDecision) : PairingResult

    data class Failed(val reason: SyncFailure) : PairingResult
}

/** The sending side: pairs with the consumer and sends new photos and videos to it (docs/PLAN.md §5). */
@Singleton
class ProducerSync @Inject constructor(
    private val identity: IdentityProvider,
    private val peers: PeerStore,
    private val ledger: SentLedger,
    private val source: MediaSource,
    private val device: DeviceInfo,
    private val discovery: ServiceDiscovery,
    private val sockets: LanSockets,
    private val history: SyncHistory,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ProducerState>(ProducerState.Idle())
    private var job: Job? = null

    /**
     * A sync is running: set until just before its outcome shows, so whoever sees that outcome may start the next
     * one right away. Its job finishes a moment later; asked again in between, *Sync now* would otherwise be ignored.
     */
    @Volatile
    private var syncing = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var pauseWhen: () -> Boolean = { false }

    val state: StateFlow<ProducerState> = _state.asStateFlow()

    suspend fun pair(link: PairingLink): PairingResult = withContext(Dispatchers.IO) {
        val connection = connectToFirst(link.addresses, link.consumer)
            ?: return@withContext PairingResult.Failed(SyncFailure.UNREACHABLE)
        try {
            connection.use {
                val outcome = PairingClient(device.local()).pair(it, link.token)
                val consumer = outcome.consumer
                if (consumer == null) {
                    PairingResult.Refused(outcome.decision)
                } else {
                    peers.register(consumer, it.remoteAddress)
                    peers.keepOnlyConsumer(consumer.id)
                    peers.recordSchedule(consumer.id, outcome.consumerSchedule)
                    PairingResult.Paired(consumer.name)
                }
            }
        } catch (_: IncompatiblePeerException) {
            PairingResult.Failed(SyncFailure.INCOMPATIBLE)
        } catch (_: IOException) {
            PairingResult.Failed(SyncFailure.CONNECTION_LOST)
        }
    }

    /** Starts sending to the paired consumer, unless a sync is already running. */
    fun syncNow() {
        launchSync(pauseWhen = { false })
    }

    /**
     * Syncs now, or joins the sync already running, and returns how it ended. For background work: if the
     * caller is cancelled (the charger was unplugged), or [pauseWhen] of the sync it started becomes true (the
     * phone got hot), the sync stops after the current file.
     */
    suspend fun syncAndWait(pauseWhen: () -> Boolean = { false }): ProducerState {
        val running = launchSync(pauseWhen)
        try {
            running.join()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
        return _state.value
    }

    @Synchronized
    private fun launchSync(pauseWhen: () -> Boolean): Job {
        job?.takeIf { syncing }?.let { return it }
        syncing = true
        cancelled = false
        this.pauseWhen = pauseWhen
        _state.value = ProducerState.Connecting
        return scope.launch(Dispatchers.IO) {
            try {
                val end = runSync()
                syncing = false
                _state.value = end
            } finally {
                syncing = false // Also when it failed unexpectedly: never block later syncs.
            }
        }.also { job = it }
    }

    /** Stops after the current file; everything confirmed so far stays recorded. */
    fun cancel() {
        cancelled = true
    }

    /** Photos and videos the consumer doesn't have yet: those that will be sent, and those withheld. */
    suspend fun pendingCount(): Pending = withContext(Dispatchers.IO) {
        val consumer = peers.consumer() ?: return@withContext Pending()
        val sent = ledger.sentFingerprints(consumer.id)
        val details = device.photoDetails()
        val waiting = source.items().filter { it.descriptor.fingerprint !in sent }
        val withheld = waiting.count { isWithheld(it.descriptor.mimeType, details) }
        Pending(toSend = waiting.size - withheld, withheld = withheld)
    }

    private suspend fun runSync(): ProducerState {
        val consumer = peers.consumer() ?: return ProducerState.Failed(SyncFailure.NOT_PAIRED)
        val details = device.photoDetails()
        Diagnostics.log(Area.SEND, "Sync to ${consumer.id.short()} (photo details: ${details.name.lowercase()})")
        val connection = connect(consumer) ?: return ProducerState.Failed(SyncFailure.UNREACHABLE)
        _state.value = ProducerState.Syncing(progress = null)
        val started = System.currentTimeMillis()
        return try {
            sockets.keepingAwake { session(consumer, connection, details, started) }
        } catch (e: IOException) {
            // Where it stopped tells which side stalled: during a file, or waiting for the other phone.
            val progress = (_state.value as? ProducerState.Syncing)?.progress
            Diagnostics.log(Area.SEND, "Sync failed after ${since(started)} at ${progress.describe()}", e)
            ProducerState.Failed(failureOf(e))
        }
    }

    private fun session(consumer: Peer, connection: PeerConnection, details: PhotoDetails, started: Long) =
        connection.use {
            val result = ProducerSession(
                device.local(),
                source,
                ledger,
                progress = { progress -> _state.value = ProducerState.Syncing(progress) },
                shouldContinue = { !cancelled && !pauseWhen() },
                details = details,
            ).sync(it)
            peers.recordAddress(consumer.id, it.remoteAddress)
            peers.recordSchedule(consumer.id, result.consumerSchedule)
            if (!result.cancelled) history.recordSync(System.currentTimeMillis(), result.deferred)
            Diagnostics.log(Area.SEND, "Sync done in ${since(started)}: ${result.describe()}")
            ProducerState.Idle(result)
        }

    private fun failureOf(error: IOException): SyncFailure = when {
        error is PeerEndedSessionException && error.byeReason == Bye.Reason.NOT_PAIRED -> SyncFailure.NOT_ALLOWED
        error is IncompatiblePeerException -> SyncFailure.INCOMPATIBLE
        else -> SyncFailure.CONNECTION_LOST
    }

    /** The last known address first (fast), then whatever the network advertises (moved to a new IP). */
    private suspend fun connect(consumer: Peer): PeerConnection? {
        consumer.lastAddress?.let { address ->
            connectToFirst(listOf(address), consumer.id)?.let { return it }
            Diagnostics.log(Area.SEND, "Not at its last address $address; looking for it on the network")
        }
        val found = discovery.find(serviceIdOf(consumer.id), DISCOVERY_TIMEOUT_MILLIS)
        if (found == null) {
            Diagnostics.log(Area.SEND, "Not found on the network within ${since(0, DISCOVERY_TIMEOUT_MILLIS)}")
            return null
        }
        return connectToFirst(listOf(found), consumer.id).also { connection ->
            Diagnostics.log(
                Area.SEND,
                if (connection ==
                    null
                ) {
                    "Found at $found, but it didn't answer"
                } else {
                    "Found at $found"
                },
            )
        }
    }

    private fun connectToFirst(addresses: List<String>, expected: DeviceId): PeerConnection? =
        addresses.asSequence().mapNotNull(::parseAddress).firstNotNullOfOrNull { address ->
            try {
                LanClient.connect(address, identity.identity(), expected, TIMEOUTS, sockets.factoryFor(address))
            } catch (e: IOException) {
                Diagnostics.log(Area.SEND, "Couldn't connect to ${address.hostString}:${address.port}", e)
                null
            }
        }

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 5_000L
        val TIMEOUTS = LanTimeouts(connectMillis = 3_000)
    }
}
