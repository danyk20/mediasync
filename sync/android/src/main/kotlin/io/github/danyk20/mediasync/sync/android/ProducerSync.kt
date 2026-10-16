/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.crypto.IdentityProvider
import io.github.danyk20.mediasync.core.database.ConsumerFacts
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.metadata.isWithheld
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.IncompatiblePeerException
import io.github.danyk20.mediasync.sync.engine.LinkKind
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
import io.github.danyk20.mediasync.sync.engine.SourceItem
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboSetup
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import io.github.danyk20.mediasync.transport.rendezvous.RelayCounter
import java.io.IOException
import java.net.InetSocketAddress
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

    /** [turbo]: over the direct link of Turbo transfer (docs/PLAN.md §8.3); [internet]: through the internet (§8.4). */
    data class Syncing(val progress: TransferProgress?, val turbo: Boolean = false, val internet: Boolean = false) :
        ProducerState

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
    private val internet = network.internet
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
                    peers.recordConsumer(consumer.id, ConsumerFacts(schedule = outcome.consumerSchedule))
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
        val order = device.sendingOrder()
        val over = if (turbo) ", Turbo asked for" else ""
        Diagnostics.log(
            Area.SEND,
            "Sync to ${consumer.id.short()} (photo details: ${details.name.lowercase()}, " +
                "order: ${order.name.lowercase()}$over)",
        )
        val run = Run(consumer, details, order, System.currentTimeMillis())
        return try {
            val connection = connect(consumer)
            when {
                connection != null && turbo -> run.overTurbo(connection)
                connection != null -> run.overWifi(connection)
                turbo -> run.overTurboReceive()
                else -> run.overInternet() ?: ProducerState.Failed(SyncFailure.UNREACHABLE)
            }
        } catch (e: IOException) {
            // Where it stopped tells which side stalled: during a file, or waiting for the other phone.
            val progress = (_state.value as? ProducerState.Syncing)?.progress
            Diagnostics.log(Area.SEND, "Sync failed after ${since(run.started)} at ${progress.describe()}", e)
            ProducerState.Failed(failureOf(e))
        }
    }

    /** One sync to [consumer]: over Wi-Fi, or over a direct link while that works. */
    private inner class Run(
        val consumer: Peer,
        val details: PhotoDetails,
        val order: SendingOrder,
        val started: Long,
    ) {
        /** On mobile data: how much more this month allows (docs/PLAN.md §2.2); `null` off mobile data. */
        var mobileDataLeft: Long? = null

        fun overWifi(connection: PeerConnection, turboProblem: TurboProblem? = null): ProducerState {
            _state.value = ProducerState.Syncing(progress = null)
            return sockets.keepingAwake { send(connection, Path.WIFI) }.copy(turboProblem = turboProblem)
        }

        /**
         * Asks the consumer for a direct link over [connection], joins it and sends over it. Whatever doesn't work
         * on the way, everything (or the rest) still goes over Wi-Fi, and the home screen says why. [aware]: asks
         * for Wi-Fi Aware, and for Wi-Fi Direct again if that can't be joined.
         */
        suspend fun overTurbo(
            connection: PeerConnection,
            aware: Boolean = directLinks.awareAvailable(),
        ): ProducerState {
            _state.value = ProducerState.SettingUpTurbo
            val answer = connection.use { TurboSetup(device.local()).request(it, aware) }
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
                outcome == null && link.kind == LinkKind.WIFI_AWARE -> {
                    Diagnostics.log(Area.SEND, "Turbo: no Wi-Fi Aware link; asking for Wi-Fi Direct instead")
                    val again = connect(consumer) ?: return ProducerState.Failed(SyncFailure.UNREACHABLE)
                    overTurbo(again, aware = false)
                }

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
            val timeout = if (link.kind == LinkKind.WIFI_AWARE) AWARE_JOIN_TIMEOUT_MILLIS else JOIN_TIMEOUT_MILLIS
            val joined = directLinks.join(link, timeout)
            if (joined == null) {
                Diagnostics.log(Area.SEND, "Turbo: couldn't join $link within ${since(0, timeout)}")
                return null
            }
            // Where the phone that opened the link is on it: it said so, but the link knows best.
            val direct = connectOver(joined, link.port, consumer.id) ?: return null
            Diagnostics.log(Area.SEND, "Turbo: sending over ${link.networkName} at ${joined.host.hostAddress}")
            _state.value = ProducerState.Syncing(progress = null, turbo = true)
            return try {
                sockets.keepingAwake { send(direct, Path.DIRECT) { connectOver(joined, link.port, consumer.id) } }
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

        /**
         * Not on a network with the consumer: through its rendezvous Worker (docs/PLAN.md §8.4), if it has one and
         * this phone isn't on mobile data. `null` if there's no way to it that way.
         */
        fun overInternet(): ProducerState? {
            val worker = consumer.rendezvousUrl ?: return null
            val secret = consumer.pairSecret ?: return null
            if (!internet.allowed()) {
                Diagnostics.log(Area.SEND, "Internet: not on Wi-Fi, and mobile data isn't allowed or its limit is used")
                return null
            }
            mobileDataLeft = internet.mobileDataLeft()
            val connection = try {
                internet.connect(consumer.id, worker, secret)
            } catch (e: IOException) {
                Diagnostics.log(Area.SEND, "Internet: no connection to the receiving phone", e)
                return null
            }
            Diagnostics.log(Area.SEND, "Internet: connected to the receiving phone")
            _state.value = ProducerState.Syncing(progress = null, internet = true)
            return sockets.keepingAwake { send(connection, Path.INTERNET) }
        }

        /**
         * Through the relay, videos wait for Wi-Fi or a direct connection unless the user lets them go (docs/PLAN.md
         * §17, relay saver): they'd use most of the free allowance.
         */
        private fun relaySaver(connection: PeerConnection): (SourceItem) -> Boolean {
            val relayed = (connection as? RelayCounter)?.throughRelay() == true
            if (!relayed || device.videosOverRelay()) return { false }
            Diagnostics.log(Area.SEND, "Internet: through the relay; videos wait for Wi-Fi or a direct connection")
            return { item -> item.descriptor.mimeType.startsWith("video/") }
        }

        /** [another]: one more connection the way [connection] went, for parallel streams. */
        private fun send(
            connection: PeerConnection,
            path: Path,
            another: () -> PeerConnection? = { connectToFirst(listOf(connection.remoteAddress), consumer.id) },
        ): ProducerState.Idle = connection.use {
            val direct = path == Path.DIRECT
            val over = when (path) {
                Path.WIFI -> ""
                Path.DIRECT -> " over Turbo"
                Path.INTERNET -> " over the internet"
            }
            // Over the internet, the phone's own order sends photos first: more of them arrive per minute.
            val order = order.resolved(overInternet = path == Path.INTERNET)
            val result = ParallelSync(
                session = { progress ->
                    ProducerSession(
                        device.local(),
                        source,
                        ledger,
                        progress,
                        { !cancelled && !pauseWhen() },
                        details,
                        order,
                    )
                },
                connect = { another() },
                progress = { progress ->
                    _state.value = ProducerState.Syncing(progress, turbo = direct, internet = path == Path.INTERNET)
                    // This month's mobile data limit reached: stops after the current file.
                    if (progress.bytesDone >= (mobileDataLeft ?: Long.MAX_VALUE)) cancelled = true
                },
                // Over the internet, one connection carries it all.
                mayAddStream = { path != Path.INTERNET && sockets.canUseMoreConnections() },
                holdBack = relaySaver(it),
            ).sync(it)
            mobileDataLeft?.let { left ->
                internet.countMobileData(result.bytesSent)
                if (result.bytesSent >=
                    left
                ) {
                    Diagnostics.log(Area.SEND, "Internet: this month's mobile data limit is used")
                }
            }
            peers.recordConsumer(
                consumer.id,
                ConsumerFacts(
                    // A direct link's address is fleeting; over the internet, there's none.
                    address = it.remoteAddress.takeIf { path == Path.WIFI },
                    schedule = result.consumerSchedule,
                    turboKey = result.consumerTurboKey,
                    pairSecret = result.consumerPairSecret,
                    rendezvousUrl = result.consumerRendezvousUrl,
                ),
            )
            if (!result.cancelled) history.recordSync(System.currentTimeMillis(), result.deferred)
            Diagnostics.log(Area.SEND, "Sync done$over in ${since(started)}: ${result.describe()}")
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
    private fun connectToFirst(addresses: List<String>, expected: DeviceId): PeerConnection? =
        addresses.asSequence().mapNotNull(::parseAddress).firstNotNullOfOrNull { address ->
            connectTo(address, expected, sockets.factoryFor(address))
        }

    /** Over a direct link this phone [joined]: through the network the link is. */
    private fun connectOver(joined: JoinedLink, port: Int, expected: DeviceId): PeerConnection? =
        connectTo(InetSocketAddress(joined.host, port), expected, joined.sockets)

    private fun connectTo(address: InetSocketAddress, expected: DeviceId, factory: SocketFactory): PeerConnection? =
        try {
            LanClient.connect(address, identity.identity(), expected, TIMEOUTS, factory)
        } catch (e: IOException) {
            Diagnostics.log(Area.SEND, "Couldn't connect to ${address.hostString}:${address.port}", e)
            null
        }

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 5_000L
        const val LINK_TEST_MILLIS = 5_000L

        /** Joining a Wi-Fi Direct group usually takes a few seconds; some phones take longer. */
        const val JOIN_TIMEOUT_MILLIS = 30_000L

        /** Wi-Fi Aware usually links up within seconds; Wi-Fi Direct is the fallback after this. */
        const val AWARE_JOIN_TIMEOUT_MILLIS = 15_000L
        val TIMEOUTS = LanTimeouts(connectMillis = 3_000)
    }
}

private fun TurboUnavailable.toProblem() = when (this) {
    TurboUnavailable.UNSUPPORTED -> TurboProblem.RECEIVER_UNSUPPORTED
    TurboUnavailable.NOT_ALLOWED -> TurboProblem.RECEIVER_NOT_ALLOWED
    TurboUnavailable.FAILED -> TurboProblem.NO_LINK
}

/** How a sync reaches the consumer (docs/PLAN.md §8). */
private enum class Path { WIFI, DIRECT, INTERNET }
