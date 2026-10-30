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
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.SpaceBudget
import io.github.danyk20.mediasync.sync.engine.SpeedSample
import io.github.danyk20.mediasync.sync.engine.TransferEstimate
import io.github.danyk20.mediasync.sync.engine.TransferEstimator
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboHost
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.LanServer
import io.github.danyk20.mediasync.transport.lan.PeerTrust
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString

/** What the consumer's home screen shows. */
data class ReceiverState(
    val listening: Boolean = false,
    val port: Int? = null,
    /** Transfers in progress, by producer. */
    val transfers: Map<DeviceId, TransferProgress> = emptyMap(),
    /**
     * How fast each producer's transfer goes, once there's enough to go on. Without the time left: a receiving phone
     * learns the total batch by batch.
     */
    val speeds: Map<DeviceId, TransferEstimate> = emptyMap(),
    /** The phone paired most recently in this run, for the pairing screen's success message. */
    val lastPaired: PairedPeer? = null,
    /** When a phone last connected or sent something; a receive window closes after a quiet spell. */
    val lastActivityMillis: Long? = null,
    /** Photos and videos the last sync offered that didn't fit in the free space. */
    val waitingForSpace: Int = 0,
    /** A direct link for Turbo transfer (docs/PLAN.md §8.3) is open. */
    val directLinkOpen: Boolean = false,
    /** A sending phone asked for Turbo transfer, which needs the user's permission here. */
    val turboNeedsPermission: Boolean = false,
    /** *Turbo receive* is on: waiting for a sending phone that isn't on this phone's network. */
    val turboReceiving: Boolean = false,
    /** Sessions running now; while there are any, receiving runs as foreground work (docs/PLAN.md §7). */
    val sessions: Int = 0,
    /** Paired phones can also reach this one through its rendezvous Worker (docs/PLAN.md §8.4). */
    val overInternet: Boolean = false,
)

/**
 * The receiving side: listens for paired phones on the local network, and through its rendezvous Worker once it
 * has one, and stores what they send (docs/PLAN.md §5, §8.2, §8.4).
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
    network: Connectivity,
    private val scope: CoroutineScope,
    private val preferredPort: Int,
    /** A direct link closes after this long without a session over it (or anywhere). */
    private val directLinkIdleMillis: Long = DIRECT_LINK_IDLE_MILLIS,
) {
    @Inject
    constructor(
        identity: IdentityProvider,
        peers: PeerStore,
        ledger: ReceivedLedger,
        sink: MediaSink,
        device: DeviceInfo,
        network: Connectivity,
        @ApplicationScope scope: CoroutineScope,
    ) : this(identity, peers, ledger, sink, device, network, scope, PREFERRED_PORT)

    private val addresses = network.addresses
    private val discovery = network.discovery
    private val directLinks = network.directLinks
    private val internet = network.internet
    private val window = PairingWindow()
    private val approvals = PairingApprovals()
    private val gate = pairingGate(window, approvals)
    private val _state = MutableStateFlow(ReceiverState())

    @Volatile
    private var server: LanServer? = null
    private var advertisement: Closeable? = null
    private var acceptJob: Job? = null
    private val internetLock = Any()

    /** What it listens for over the internet now, and the handle that stops it; under [internetLock]. */
    private var overInternet: Pair<InternetSetup, Closeable>? = null
    private val holders = mutableSetOf<String>()

    /** One budget for every session at once: a sender's parallel connections, or several senders. */
    private val space = SpaceBudget(sink)

    /** Progress of each session running now, by session; a sender may have several (docs/PLAN.md §7). */
    private val sessions = ConcurrentHashMap<Long, Pair<DeviceId, TransferProgress>>()
    private val nextSession = AtomicLong()
    private val activeSessions = AtomicInteger()

    /**
     * The bytes each producer's ended sessions of this sync brought over the link: its speed counts all its
     * connections, also once one of them is done before the others.
     */
    private val endedBytes = ConcurrentHashMap<DeviceId, Long>()
    private val estimators = ConcurrentHashMap<DeviceId, TransferEstimator>()

    /** When each producer's last session ended: one that comes back soon after is the same sync going on. */
    private val quietSince = ConcurrentHashMap<DeviceId, Long>()

    /**
     * Held while a producer's sessions are added up: each session reports on its own thread, and a total from one
     * moment must not come after a total from the next.
     */
    private val tally = Any()
    private var speedsJob: Job? = null
    private val _speedHistory = MutableStateFlow<Map<DeviceId, List<SpeedSample>>>(emptyMap())

    /** Each producer's speed every second of its sync running now, or of its last one, for the chart. */
    val speedHistory: StateFlow<Map<DeviceId, List<SpeedSample>>> = _speedHistory.asStateFlow()

    /** Turbo transfer, as the receiving phone (docs/PLAN.md §8.3). */
    val turbo = Turbo()

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
        speedsJob = scope.launch { refreshSpeeds() }
        Diagnostics.log(Area.RECEIVE, "Listening on port ${server.port} for ${holders.joinToString()}")
        refreshInternet()
    }

    /**
     * While listening: listens over the internet as set up now (docs/PLAN.md §8.4), after its Worker changed or a
     * sending phone got its pair secret (in its first session).
     */
    fun refreshInternet(again: Boolean = false) {
        scope.launch(Dispatchers.IO) { listenOverInternet(again) }
    }

    /** [again]: even as set up already, which registers this phone with its Worker anew (a new FCM token). */
    private fun listenOverInternet(again: Boolean = false) = synchronized(internetLock) {
        val worker = device.local().rendezvousUrl
        val pairs = peers.producers().mapNotNull { peer -> peer.pairSecret?.let { peer.id to it } }.toMap()
        val wanted = worker?.takeIf { pairs.isNotEmpty() && server != null }?.let { InternetSetup(it, pairs) }
        if (wanted == overInternet?.first && !again) return@synchronized
        overInternet?.second?.close()
        overInternet = wanted?.let { it to internet.listen(it.worker, it.pairs, ::serveOverInternet) }
        _state.update { it.copy(overInternet = wanted != null) }
        if (wanted != null) Diagnostics.log(Area.RECEIVE, "Internet: listening for ${pairs.size} sending phones")
    }

    private fun stopListeningOverInternet() = synchronized(internetLock) {
        overInternet?.second?.close()
        overInternet = null
    }

    @Synchronized
    fun stop() {
        turbo.stopReceive()
        if (server != null) Diagnostics.log(Area.RECEIVE, "Stopped listening")
        acceptJob?.cancel()
        speedsJob?.cancel()
        synchronized(tally) {
            estimators.clear()
            endedBytes.clear()
            quietSince.clear()
        }
        server?.close()
        advertisement?.close()
        server = null
        advertisement = null
        stopListeningOverInternet()
        _state.update {
            it.copy(listening = false, port = null, transfers = emptyMap(), speeds = emptyMap(), overInternet = false)
        }
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

    /**
     * This month's relay usage at this phone's Worker (docs/PLAN.md §8.5); `null` without internet sync, before a
     * sending phone could use it, or while the Worker can't be asked. Blocks.
     */
    fun relayUsage(): RelayUsage? {
        val worker = device.local().rendezvousUrl ?: return null
        val producer = peers.producers().firstOrNull { it.pairSecret != null } ?: return null
        return try {
            internet.relayUsage(worker, producer.id)
        } catch (e: IOException) {
            Diagnostics.log(Area.RECEIVE, "Internet: couldn't read the relay meter", e)
            null
        }
    }

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
        Diagnostics.log(Area.RECEIVE, "Session from ${connection.peerId.short()} at ${connection.remoteAddress}")
        counted(connection)
    }

    private fun serveOverInternet(connection: PeerConnection) {
        Diagnostics.log(
            Area.RECEIVE,
            "Session from ${connection.peerId.short()} over the internet, ${connection.internetRoute()}",
        )
        counted(connection)
        relayUsage() // It may have gone through the relay: the meter, read, tells the user if it runs low.
    }

    /** A session, counted while it runs: receiving then runs as foreground work (docs/PLAN.md §7). */
    private fun counted(connection: PeerConnection) {
        val started = System.currentTimeMillis()
        markActivity()
        countSession(+1)
        try {
            addresses.keepingAwake { receive(connection.peerId, connection, started) }
        } finally {
            countSession(-1)
            markActivity()
            if (server != null) listenOverInternet() // It may have just got its pair secret.
        }
    }

    /** One session with [producer], from its first message to its end. */
    private fun receive(producer: DeviceId, connection: PeerConnection, started: Long) {
        val id = nextSession.incrementAndGet()
        sessionStarts(producer)
        connection.use {
            val session = ConsumerSession(
                device.local(),
                peers,
                ledger,
                sink,
                gate,
                progress = { progress ->
                    synchronized(tally) {
                        sessions[id] = producer to progress
                        val transfers = transfers()
                        _state.update {
                            it.copy(
                                transfers = transfers,
                                speeds = speeds(transfers),
                                lastActivityMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                },
                space = space,
                turbo = turbo.host,
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
            synchronized(tally) {
                // Its bytes stay in the producer's total: they move from its session to what ended.
                sessions.remove(id)?.let { (_, last) -> endedBytes.merge(producer, last.bytesMoved, Long::plus) }
                if (sessions.values.none { it.first == producer }) quietSince[producer] = System.currentTimeMillis()
                val transfers = transfers()
                _state.update { state ->
                    state.copy(
                        transfers = transfers,
                        speeds = speeds(transfers),
                        lastPaired = (result as? ServeResult.Paired)?.peer ?: state.lastPaired,
                        lastActivityMillis = System.currentTimeMillis(),
                        waitingForSpace = (result as? ServeResult.Synced)?.waitingForSpace ?: state.waitingForSpace,
                    )
                }
            }
        }
    }

    /**
     * A session from [producer] begins. Soon after its last one ended, it's the same sync going on: its connections
     * may all end at once, as when it swaps Wi-Fi for Turbo. Later, it's another sync, and its speed starts afresh.
     */
    private fun sessionStarts(producer: DeviceId) = synchronized(tally) {
        val quiet = quietSince.remove(producer) ?: return@synchronized
        if (System.currentTimeMillis() - quiet > SAME_SYNC_GAP_MILLIS) {
            endedBytes.remove(producer)
            estimators[producer]?.reset()
        }
    }

    /** Each producer's speed now, counting the sessions of this sync that ended already. */
    private fun speeds(transfers: Map<DeviceId, TransferProgress>): Map<DeviceId, TransferEstimate> =
        transfers.mapNotNull { (producer, progress) ->
            val all = progress.copy(bytesDone = progress.bytesDone + (endedBytes[producer] ?: 0L))
            estimators.getOrPut(producer) { TransferEstimator() }.update(all)?.let { producer to it }
        }.toMap()

    /** Keeps the speeds current every second, also while nothing comes: a stalled link slows down to nothing. */
    private suspend fun refreshSpeeds() {
        while (true) {
            delay(SPEED_REFRESH_MILLIS)
            val receiving = _state.value.transfers.keys
            if (receiving.isEmpty()) continue
            val speeds = receiving.mapNotNull { producer -> estimators[producer]?.refresh()?.let { producer to it } }
            // A producer whose sync ended keeps its last chart until its next sync.
            _speedHistory.update { shown ->
                shown + receiving.mapNotNull { producer -> estimators[producer]?.let { producer to it.history() } }
            }
            _state.update { state ->
                state.copy(speeds = speeds.filter { (producer, _) -> producer in state.transfers }.toMap())
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
                    bytesSkipped = a.bytesSkipped + b.bytesSkipped,
                )
            }
        }

    private fun markActivity() = _state.update { it.copy(lastActivityMillis = System.currentTimeMillis()) }

    private fun countSession(change: Int) {
        val now = activeSessions.addAndGet(change)
        _state.update { it.copy(sessions = now) }
    }

    /**
     * Turbo transfer, as the receiving phone (docs/PLAN.md §8.3): the direct links sending phones join, one at a
     * time, whether they asked over Wi-Fi or this phone's user started *Turbo receive*.
     */
    inner class Turbo internal constructor() {
        /** The direct link open now, for every sending phone that asks; `null` while there's none. */
        private val link = AtomicReference<TurboAnswer.Ready?>(null)

        /** One link opened at a time. Never held while calling into the receiver's synchronized methods. */
        private val opening = Any()

        /** Answers sending phones that ask over Wi-Fi. */
        internal val host = TurboHost { aware -> open(aware = aware) }

        /** Whether sending phones can use Turbo transfer with this phone now. */
        fun availability(): TurboAvailability = directLinks.availability()

        /** The user allowed Turbo here, or dismissed the request: stop asking. */
        fun permissionHandled() = _state.update { it.copy(turboNeedsPermission = false) }

        /**
         * *Turbo receive*: opens a direct link that paired sending phones find without a network in common, as its
         * name and passphrase come from this phone's Turbo key and the time. It stays open until [stopReceive], or
         * 10 minutes without a session over it. Blocks while Wi-Fi Direct sets it up.
         */
        fun startReceive(): TurboAnswer {
            hold(TURBO_RECEIVE_HOLDER) // Listening, so there's a port to offer.
            try {
                // A link opened for a sending phone on this Wi-Fi has a random name: replaced by one others can find.
                link.get()?.let { open -> if (forget(open)) directLinks.close(open.link.networkName) }
                val key = device.local().turboKey ?: return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
                val credentials = DirectLink.derived(key, DirectLink.epochAt(System.currentTimeMillis()))
                return open(credentials, TURBO_RECEIVE_IDLE_MILLIS).also { answer ->
                    if (answer is TurboAnswer.Ready) _state.update { it.copy(turboReceiving = true) }
                }
            } finally {
                release(TURBO_RECEIVE_HOLDER) // The link holds on to listening itself while it's open.
            }
        }

        /** Ends *Turbo receive*, or any direct link open. Never waits, so the main thread may call it. */
        fun stopReceive() {
            link.get()?.let(::close)
        }

        /**
         * Opens a direct link for a sending phone that asked for Turbo transfer, or hands out the one already open.
         * It stays open, and this phone keeps listening, until [idleMillis] after the last session. [aware]: the
         * sending phone can join over Wi-Fi Aware, which is then tried first.
         */
        private fun open(
            credentials: Pair<String, String> = DirectLink.credentials(),
            idleMillis: Long = directLinkIdleMillis,
            aware: Boolean = false,
        ): TurboAnswer {
            // A Wi-Fi Aware link only suits phones that can join one: it makes way for Wi-Fi Direct, while this
            // phone keeps listening.
            link.get()?.takeIf { !aware && it.link.kind == LinkKind.WIFI_AWARE }?.let { old ->
                hold(DIRECT_LINK_SWAP_HOLDER)
                try {
                    if (forget(old)) directLinks.close(old.link.networkName)
                    return open(credentials, idleMillis, aware)
                } finally {
                    release(DIRECT_LINK_SWAP_HOLDER)
                }
            }
            var opened: TurboAnswer.Ready? = null
            val answer = synchronized(opening) {
                link.get() ?: run {
                    val port = _state.value.port ?: return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
                    openLink(port, credentials, aware).also { answer ->
                        Diagnostics.log(Area.RECEIVE, "Turbo: ${answer.describe()}")
                        if (answer is TurboAnswer.Ready) {
                            link.set(answer)
                            opened = answer
                        }
                    }
                }
            }
            markActivity()
            val new = opened
            when {
                new != null -> {
                    hold(DIRECT_LINK_HOLDER)
                    _state.update { it.copy(directLinkOpen = true, turboNeedsPermission = false) }
                    scope.launch { closeWhenIdle(new, idleMillis) }
                }

                answer is TurboAnswer.Unavailable -> _state.update {
                    it.copy(turboNeedsPermission = answer.reason == TurboUnavailable.NOT_ALLOWED)
                }
            }
            return answer
        }

        /** Over Wi-Fi Aware if [aware] and this phone can, else (or if that fails) over Wi-Fi Direct. */
        private fun openLink(port: Int, credentials: Pair<String, String>, aware: Boolean): TurboAnswer {
            if (aware && directLinks.awareAvailable()) {
                val answer = directLinks.open(port, DirectLink.awareCredentials(), LinkKind.WIFI_AWARE)
                if (answer is TurboAnswer.Ready) return answer
                Diagnostics.log(Area.RECEIVE, "Turbo: Wi-Fi Aware ${answer.describe()}; Wi-Fi Direct instead")
            }
            return directLinks.open(port, credentials)
        }

        /** Closes [open] once nothing came over it (or anywhere) for [idleMillis]; done if it closed before. */
        private suspend fun closeWhenIdle(open: TurboAnswer.Ready, idleMillis: Long) {
            while (link.get() === open) {
                delay(minOf(idleMillis, IDLE_CHECK_MILLIS))
                val quietFor = System.currentTimeMillis() - (_state.value.lastActivityMillis ?: 0L)
                if (activeSessions.get() == 0 && quietFor >= idleMillis) close(open)
            }
        }

        private fun close(open: TurboAnswer.Ready) {
            if (!forget(open)) return
            // Waits for Wi-Fi Direct: never on the main thread.
            scope.launch(Dispatchers.IO) { directLinks.close(open.link.networkName) }
        }

        /** Marks [open] closed, if it's the one open; `true` if it was. */
        private fun forget(open: TurboAnswer.Ready): Boolean {
            if (!link.compareAndSet(open, null)) return false
            Diagnostics.log(Area.RECEIVE, "Turbo: direct link closed")
            _state.update { it.copy(directLinkOpen = false, turboReceiving = false) }
            release(DIRECT_LINK_HOLDER)
            return true
        }
    }

    companion object {
        /** Fixed when possible, so a phone's last known address keeps working. */
        const val PREFERRED_PORT = 47_420
        private const val MAX_NAME = 64
        private const val DIRECT_LINK_HOLDER = "turbo"
        private const val DIRECT_LINK_SWAP_HOLDER = "turbo-swap"
        private const val TURBO_RECEIVE_HOLDER = "turbo-receive"
        private const val SPEED_REFRESH_MILLIS = 1_000L

        /** A producer's sessions this close together are one sync: long enough for its swap to Turbo. */
        private const val SAME_SYNC_GAP_MILLIS = 60_000L

        /** Time for the user to walk over to the sending phone and start Turbo there. */
        private const val TURBO_RECEIVE_IDLE_MILLIS = 10 * 60_000L

        /** Time for the sending phone to join the link, and to come back after a short break. */
        private const val DIRECT_LINK_IDLE_MILLIS = 60_000L
        private const val IDLE_CHECK_MILLIS = 5_000L
    }
}

/** What a receiving phone listens for over the internet: its Worker, and each sending phone's pair secret. */
private data class InternetSetup(val worker: String, val pairs: Map<DeviceId, ByteString>) {
    override fun toString() = "InternetSetup(${pairs.size} sending phones)"
}
