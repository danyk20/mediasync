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
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.IncompatiblePeerException
import io.github.danyk20.mediasync.sync.engine.LinkSpeed
import io.github.danyk20.mediasync.sync.engine.LinkTest
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.PairingClient
import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.github.danyk20.mediasync.sync.engine.ParallelSync
import io.github.danyk20.mediasync.sync.engine.PeerEndedSessionException
import io.github.danyk20.mediasync.sync.engine.ProducerSession
import io.github.danyk20.mediasync.sync.engine.SentLedger
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboSetup
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
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

    /** Turbo: not reached over Wi-Fi, and no *Turbo receive* link of it nearby. */
    TURBO_UNREACHABLE,

    /** Turbo: not reached over Wi-Fi, and it hasn't told its Turbo key yet (one sync over Wi-Fi does). */
    TURBO_NOT_SET_UP,
}

/** Why Turbo transfer (docs/PLAN.md §8.3) wasn't used, or not throughout: the rest went over Wi-Fi. */
enum class TurboProblem {
    /** The receiving phone has no Wi-Fi Direct. */
    RECEIVER_UNSUPPORTED,

    /** The receiving phone's user hasn't allowed MediaSync to connect to nearby devices. */
    RECEIVER_NOT_ALLOWED,

    /** The direct link couldn't be set up or joined. */
    NO_LINK,

    /** The direct link dropped during the transfer. */
    DROPPED,
}

/** What the producer's home screen shows. */
sealed interface ProducerState {
    /** [turboProblem]: Turbo was asked for but not (fully) used, and why. */
    data class Idle(val lastResult: SyncResult? = null, val turboProblem: TurboProblem? = null) : ProducerState

    data object Connecting : ProducerState

    /** Asking the receiving phone for a direct link, and joining it. */
    data object SettingUpTurbo : ProducerState

    /** [turbo]: over the direct link of Turbo transfer. */
    data class Syncing(val progress: TransferProgress?, val turbo: Boolean = false) : ProducerState

    data class Failed(val reason: SyncFailure) : ProducerState
}

/**
 * What the consumer doesn't have yet: [toSend], and [withheld] by the *Photo details* setting, whose details can't
 * be removed (RAW photos under *Remove all*, formats MediaSync can't read).
 */
data class Pending(val toSend: Int = 0, val withheld: Int = 0, val bytes: Long = 0)

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
    network: Connectivity,
    private val history: SyncHistory,
    private val conditions: DeviceConditions,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val discovery = network.discovery
    private val sockets = network.sockets
    private val directLinks = network.directLinks
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
                    peers.recordConsumer(consumer.id, schedule = outcome.consumerSchedule)
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
        launchSync(pauseWhen = { false }, turbo = false)
    }

    /** Whether this phone can use Turbo transfer (docs/PLAN.md §8.3) now. */
    fun turboAvailability(): TurboAvailability = directLinks.availability()

    /** Turbo uses more power: worth a warning below 30 % without a charger. */
    fun turboBatteryLow(): Boolean = conditions.lowForTurbo()

    /**
     * Syncs now, or joins the sync already running, and returns how it ended. For background work: if the
     * caller is cancelled (the charger was unplugged), or [pauseWhen] of the sync it started becomes true (the
     * phone got hot), the sync stops after the current file. With [turbo], over a direct link (docs/PLAN.md §8.3)
     * if one can be set up, else over Wi-Fi.
     */
    suspend fun syncAndWait(pauseWhen: () -> Boolean = { false }, turbo: Boolean = false): ProducerState {
        val running = launchSync(pauseWhen, turbo)
        try {
            running.join()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
        return _state.value
    }

    @Synchronized
    private fun launchSync(pauseWhen: () -> Boolean, turbo: Boolean): Job {
        job?.takeIf { syncing }?.let { return it }
        syncing = true
        cancelled = false
        this.pauseWhen = pauseWhen
        _state.value = ProducerState.Connecting
        return scope.launch(Dispatchers.IO) {
            try {
                val end = runSync(turbo)
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

    /**
     * The link test (docs/PLAN.md §7): how fast this phone can send to the receiving phone over [streams] connections
     * at once, for [durationMillis]. `null` if it couldn't be reached; the diagnostic log says why.
     */
    suspend fun linkTest(streams: Int, durationMillis: Long = LINK_TEST_MILLIS): LinkSpeed? =
        withContext(Dispatchers.IO) {
            val consumer = peers.consumer() ?: return@withContext null
            val first = connect(consumer) ?: return@withContext null
            val connections =
                listOf(first) + List(streams - 1) { connectToFirst(listOf(first.remoteAddress), consumer.id) }
                    .filterNotNull()
            val pool = Executors.newFixedThreadPool(connections.size)
            try {
                sockets.keepingAwake {
                    connections.map { connection ->
                        pool.submit<LinkSpeed> { LinkTest(device.local(), durationMillis).run(connection) }
                    }
                        .map { it.get() }
                        .reduce(LinkSpeed::plus)
                }.also { speed ->
                    Diagnostics.log(Area.SEND, "Link test over ${connections.size} connection(s): ${speed.describe()}")
                }
            } catch (e: ExecutionException) {
                Diagnostics.log(Area.SEND, "Link test failed", e.cause ?: e)
                null
            } finally {
                pool.shutdownNow()
                connections.forEach { runCatching { it.close() } }
            }
        }

    /** Photos and videos the consumer doesn't have yet: those that will be sent, and those withheld. */
    suspend fun pendingCount(): Pending = withContext(Dispatchers.IO) {
        val consumer = peers.consumer() ?: return@withContext Pending()
        val sent = ledger.sentFingerprints(consumer.id)
        val details = device.photoDetails()
        val waiting = source.items().filter { it.descriptor.fingerprint !in sent }
        val (held, toSend) = waiting.partition { isWithheld(it.descriptor.mimeType, details) }
        Pending(toSend = toSend.size, withheld = held.size, bytes = toSend.sumOf { it.descriptor.size })
    }

    private suspend fun runSync(turbo: Boolean): ProducerState {
        val consumer = peers.consumer() ?: return ProducerState.Failed(SyncFailure.NOT_PAIRED)
        val details = device.photoDetails()
        val over = if (turbo) ", Turbo asked for" else ""
        Diagnostics.log(Area.SEND, "Sync to ${consumer.id.short()} (photo details: ${details.name.lowercase()}$over)")
        val run = Run(consumer, details, System.currentTimeMillis())
        return try {
            val connection = connect(consumer)
            when {
                connection != null && turbo -> run.overTurbo(connection)
                connection != null -> run.overWifi(connection)
                turbo -> run.overTurboReceive()
                else -> ProducerState.Failed(SyncFailure.UNREACHABLE)
            }
        } catch (e: IOException) {
            // Where it stopped tells which side stalled: during a file, or waiting for the other phone.
            val progress = (_state.value as? ProducerState.Syncing)?.progress
            Diagnostics.log(Area.SEND, "Sync failed after ${since(run.started)} at ${progress.describe()}", e)
            ProducerState.Failed(failureOf(e))
        }
    }

    /** One sync to [consumer]: over Wi-Fi, or over a direct link while that works. */
    private inner class Run(val consumer: Peer, val details: PhotoDetails, val started: Long) {

        fun overWifi(connection: PeerConnection, turboProblem: TurboProblem? = null): ProducerState {
            _state.value = ProducerState.Syncing(progress = null)
            return sockets.keepingAwake { send(connection, direct = false) }.copy(turboProblem = turboProblem)
        }

        /**
         * Asks the consumer for a direct link over [connection], joins it and sends over it. Whatever doesn't work
         * on the way, everything (or the rest) still goes over Wi-Fi, and the home screen says why.
         */
        suspend fun overTurbo(connection: PeerConnection): ProducerState {
            _state.value = ProducerState.SettingUpTurbo
            val answer = connection.use { TurboSetup(device.local()).request(it) }
            Diagnostics.log(Area.SEND, "Turbo: the receiving phone is ${answer.describe()}")
            val link = when (answer) {
                is TurboAnswer.Ready -> answer.link
                is TurboAnswer.Unavailable -> return wifiAgain(answer.reason.toProblem())
            }
            val outcome = try {
                overDirectLink(link)
            } finally {
                directLinks.leave()
            }
            return when {
                outcome == null -> wifiAgain(TurboProblem.NO_LINK)

                outcome is ProducerState.Idle && outcome.turboProblem == TurboProblem.DROPPED -> wifiAgain(
                    TurboProblem.DROPPED,
                )

                else -> outcome
            }
        }

        /**
         * Not on a network with the consumer: joins its *Turbo receive* link (docs/PLAN.md §8.3), whose name and
         * passphrase come from the consumer's Turbo key and the time it was started, in this epoch or the last.
         */
        suspend fun overTurboReceive(): ProducerState {
            val key = consumer.turboKey ?: return ProducerState.Failed(SyncFailure.TURBO_NOT_SET_UP).also {
                Diagnostics.log(Area.SEND, "Turbo: no key from the receiving phone yet; one sync over Wi-Fi tells it")
            }
            _state.value = ProducerState.SettingUpTurbo
            val port = consumer.lastAddress?.let(::parseAddress)?.port ?: ConsumerReceiver.PREFERRED_PORT
            val epoch = DirectLink.epochAt(System.currentTimeMillis())
            for (started in listOf(epoch, epoch - 1)) {
                val (name, passphrase) = DirectLink.derived(key, started)
                val outcome = try {
                    overDirectLink(DirectLink(name, passphrase, host = "", port = port))
                } finally {
                    directLinks.leave()
                }
                // Over a link of its own, nothing to go on over: what's left goes next time.
                if (outcome is ProducerState.Idle && outcome.turboProblem == TurboProblem.DROPPED) {
                    return ProducerState.Failed(SyncFailure.CONNECTION_LOST)
                }
                if (outcome != null) return outcome
            }
            Diagnostics.log(Area.SEND, "Turbo: no Turbo receive link of the receiving phone nearby")
            return ProducerState.Failed(SyncFailure.TURBO_UNREACHABLE)
        }

        /** How it ended over [link]; `null` if it couldn't be joined. A link dropping goes on over Wi-Fi. */
        private suspend fun overDirectLink(link: DirectLink): ProducerState? {
            val owner = directLinks.join(link, JOIN_TIMEOUT_MILLIS)
            if (owner == null) {
                Diagnostics.log(Area.SEND, "Turbo: couldn't join $link within ${since(0, JOIN_TIMEOUT_MILLIS)}")
                return null
            }
            // Where the phone that opened the link is on it: it said so, but the link knows best.
            val direct = connectToFirst(listOf("$owner:${link.port}"), consumer.id, direct = true) ?: return null
            Diagnostics.log(Area.SEND, "Turbo: sending over ${link.networkName} at $owner:${link.port}")
            _state.value = ProducerState.Syncing(progress = null, turbo = true)
            return try {
                sockets.keepingAwake { send(direct, direct = true) }
            } catch (e: IOException) {
                if (failureOf(e) != SyncFailure.CONNECTION_LOST) throw e
                Diagnostics.log(Area.SEND, "Turbo: the direct link dropped; going on over Wi-Fi", e)
                ProducerState.Idle(turboProblem = TurboProblem.DROPPED)
            }
        }

        /** Back on Wi-Fi after Turbo didn't work out; the rest goes there. */
        private suspend fun wifiAgain(problem: TurboProblem): ProducerState {
            val connection = connect(consumer) ?: return ProducerState.Failed(SyncFailure.UNREACHABLE)
            return overWifi(connection, problem)
        }

        private fun send(connection: PeerConnection, direct: Boolean): ProducerState.Idle = connection.use {
            val turbo = if (direct) " over Turbo" else ""
            val result = ParallelSync(
                session = { progress ->
                    ProducerSession(device.local(), source, ledger, progress, { !cancelled && !pauseWhen() }, details)
                },
                connect = { connectToFirst(listOf(it.remoteAddress), consumer.id, direct) },
                progress = { progress -> _state.value = ProducerState.Syncing(progress, turbo = direct) },
                mayAddStream = sockets::canUseMoreConnections,
            ).sync(it)
            peers.recordConsumer(
                consumer.id,
                address = it.remoteAddress.takeUnless { direct }, // A direct link's address is fleeting.
                schedule = result.consumerSchedule,
                turboKey = result.consumerTurboKey,
                pairSecret = result.consumerPairSecret,
            )
            if (!result.cancelled) history.recordSync(System.currentTimeMillis(), result.deferred)
            Diagnostics.log(Area.SEND, "Sync done$turbo in ${since(started)}: ${result.describe()}")
            ProducerState.Idle(result)
        }
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

    /** [direct]: over a direct link, which Android routes by itself, not through a Wi-Fi network. */
    private fun connectToFirst(addresses: List<String>, expected: DeviceId, direct: Boolean = false): PeerConnection? =
        addresses.asSequence().mapNotNull(::parseAddress).firstNotNullOfOrNull { address ->
            try {
                val factory = if (direct) SocketFactory.getDefault() else sockets.factoryFor(address)
                LanClient.connect(address, identity.identity(), expected, TIMEOUTS, factory)
            } catch (e: IOException) {
                Diagnostics.log(Area.SEND, "Couldn't connect to ${address.hostString}:${address.port}", e)
                null
            }
        }

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 5_000L
        const val LINK_TEST_MILLIS = 5_000L

        /** Joining a Wi-Fi Direct group usually takes a few seconds; some phones take longer. */
        const val JOIN_TIMEOUT_MILLIS = 30_000L
        val TIMEOUTS = LanTimeouts(connectMillis = 3_000)
    }
}

private fun TurboUnavailable.toProblem() = when (this) {
    TurboUnavailable.UNSUPPORTED -> TurboProblem.RECEIVER_UNSUPPORTED
    TurboUnavailable.NOT_ALLOWED -> TurboProblem.RECEIVER_NOT_ALLOWED
    TurboUnavailable.FAILED -> TurboProblem.NO_LINK
}
