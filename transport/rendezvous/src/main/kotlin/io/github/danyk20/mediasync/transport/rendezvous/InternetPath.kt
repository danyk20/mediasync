/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.OkHttpClient

/** How the two phones' signals travel: through the Worker's mailbox, sealed (PLAN §8.4). */
interface SignalPipe {
    fun send(signal: Signal)

    /** Signals from the other phone, as they arrive. */
    val incoming: BlockingQueue<Signal>
}

/** A connection that may go through the TURN relay, which counts against the relay allowance (PLAN §8.5). */
interface RelayCounter {
    /** Bytes sent and received through the relay so far; 0 while the phones are connected directly. */
    fun relayedBytes(): Long

    /** Whether the connection goes through the relay now, rather than directly between the phones. */
    fun throughRelay(): Boolean = false
}

/** A connection between the two phones that signals set up: WebRTC (transport:webrtc). */
interface PeerLinks {
    /** As the producer: offers, and returns the connection to [peer] once it's open. Blocks. */
    fun connect(peer: DeviceId, iceServers: List<IceServer>, pipe: SignalPipe, timeoutMillis: Long): PeerConnection

    /** As the consumer: answers [offer], and returns the connection to [peer] once it's open. Blocks. */
    fun accept(
        peer: DeviceId,
        offer: Signal.Offer,
        iceServers: List<IceServer>,
        pipe: SignalPipe,
        timeoutMillis: Long,
    ): PeerConnection
}

/**
 * Internet sync's way between the paired phones (PLAN §8.4): the receiving phone's rendezvous Worker introduces them.
 * Its mailbox carries their offers, answers and candidates, sealed with the pair's secret (ADR 0009), until [links]
 * has a connection of its own between them.
 *
 * @param log what happened, for the diagnostic log; never names files.
 */
class InternetPath(
    private val signer: RendezvousSigner,
    private val links: PeerLinks,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val okHttp: OkHttpClient = OkHttpClient(),
    private val timeouts: Timeouts = Timeouts(),
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /** In milliseconds. */
    data class Timeouts(
        /** For the Worker to open the mailbox. */
        val ready: Long = 15_000,
        /** For a receiving phone that was woken to come online. */
        val wake: Long = 30_000,
        /** For the connection to open, once both phones are there. */
        val connect: Long = 45_000,
        /** Before the first retry of a mailbox that dropped; each next waits twice as long, up to 5 minutes. */
        val retry: Long = 5_000,
        /** Between reports of the bytes relayed (PLAN §8.5: every minute). */
        val relayReport: Long = 60_000,
    )

    /**
     * As the producer: a connection to [consumer] through its Worker at [worker]. A consumer that isn't online is
     * woken (PLAN §10.4) and waited for. Blocks.
     *
     * @throws IOException if there's no way to the consumer now; the message says why.
     */
    fun connect(consumer: DeviceId, worker: URI, pairSecret: ByteArray): PeerConnection {
        val pair = pairId(consumer, signer.deviceId)
        val client = RendezvousClient(worker, signer, http)
        val ice = client.iceServers(pair)
        if (!ice.relay.available) log("The relay is unavailable (${ice.relay.reason}); direct connections only", null)
        val queue = LinkedBlockingQueue<Signal>()
        val mailbox = Mailbox(worker, pair, pairSecret, onSignal = queue::put)
        try {
            mailbox.open()
            when (mailbox.awaitReady(timeouts.ready)) {
                null -> throw IOException("The rendezvous Worker didn't open the mailbox", mailbox.failure)
                false -> wake(client, pair, mailbox)
                true -> Unit
            }
            val connection = links.connect(consumer, ice.iceServers, mailbox.pipe(queue), timeouts.connect)
            return InternetConnection(connection, mailbox, client, pair)
        } catch (e: IOException) {
            mailbox.close()
            throw e
        }
    }

    private fun wake(client: RendezvousClient, pair: String, mailbox: Mailbox) {
        try {
            client.wake(pair)
        } catch (e: RendezvousException) {
            throw IOException("The receiving phone isn't online, and couldn't be woken (${e.code})", e)
        }
        log("The receiving phone isn't online; woke it", null)
        if (!mailbox.awaitPeer(timeouts.wake)) {
            throw IOException("The receiving phone didn't come online within ${timeouts.wake / MILLIS_PER_SECOND} s")
        }
    }

    /** As the consumer: this month's relay usage (PLAN §8.5) at its Worker at [worker], as the pair with [producer]. */
    fun relayMeter(worker: URI, producer: DeviceId): RelayMeter =
        RendezvousClient(worker, signer, http).relayUsage(pairId(signer.deviceId, producer))

    /**
     * As the consumer: registers each pair in [pairs] (producer → pair secret) at its Worker at [worker], and answers
     * those producers' offers through its mailbox. Each connection goes to [serve], on a thread of its own, until the
     * returned handle is closed. A mailbox that drops is opened again. Doesn't block. [pushToken], this phone's FCM
     * token, is registered with each pair, so a producer can wake this phone through the Worker (PLAN §10.4).
     */
    fun listen(
        worker: URI,
        pairs: Map<DeviceId, ByteArray>,
        pushToken: String? = null,
        serve: (PeerConnection) -> Unit,
    ): Closeable {
        val threads = Executors.newCachedThreadPool(daemon("mediasync-internet"))
        val retries = Executors.newSingleThreadScheduledExecutor(daemon("mediasync-internet-retry"))
        val listeners = pairs.map { (producer, secret) ->
            PairListener(worker, producer, secret, threads, retries, pushToken, serve)
        }
        listeners.forEach { threads.execute(it::start) }
        return Closeable {
            listeners.forEach(PairListener::close)
            retries.shutdownNow()
            threads.shutdown() // Sessions under way go on to their end, as over Wi-Fi.
        }
    }

    /** The consumer's side of one pair: its mailbox, kept open, and the producer's attempts to connect through it. */
    private inner class PairListener(
        private val worker: URI,
        private val producer: DeviceId,
        private val secret: ByteArray,
        private val threads: ExecutorService,
        private val retries: ScheduledExecutorService,
        private val pushToken: String?,
        private val serve: (PeerConnection) -> Unit,
    ) {
        private val pair = pairId(signer.deviceId, producer)
        private val client = RendezvousClient(worker, signer, http)

        /** The incoming signals of each attempt, by its session. */
        private val attempts = ConcurrentHashMap<String, BlockingQueue<Signal>>()

        @Volatile
        private var closed = false

        @Volatile
        private var mailbox: Mailbox? = null
        private var retryMillis = timeouts.retry

        fun start() {
            val open = try {
                client.registerPair(producer, pushToken?.let(PushToken::Of) ?: PushToken.Keep)
                synchronized(this) {
                    if (closed) return
                    Mailbox(worker, pair, secret, ::onSignal, ::onDown).also {
                        mailbox = it
                        it.open()
                    }
                }
            } catch (e: RendezvousException) {
                log("The rendezvous Worker refused the pair", e)
                onDown(reconnect = e.status !in CLIENT_ERRORS) // Refused for good, unless it was busy.
                return
            } catch (e: IOException) {
                log("Couldn't register a pair at the rendezvous Worker", e)
                onDown(reconnect = true)
                return
            }
            when (open.awaitReady(timeouts.ready)) {
                null -> open.drop()

                // Not open in time: tried again.
                else -> retryMillis = timeouts.retry
            }
        }

        private fun onSignal(signal: Signal) {
            if (closed) return
            if (signal is Signal.Offer) {
                val queue = LinkedBlockingQueue<Signal>()
                attempts[signal.session] = queue
                threads.execute { answer(signal, queue) }
            } else {
                attempts[signal.session]?.put(signal) // Of an attempt that ended: too late.
            }
        }

        private fun answer(offer: Signal.Offer, queue: BlockingQueue<Signal>) {
            val connection = try {
                val ice = client.iceServers(pair)
                val open = mailbox ?: return
                links.accept(producer, offer, ice.iceServers, open.pipe(queue), timeouts.connect)
            } catch (e: IOException) {
                log("A sending phone's internet connection didn't open", e)
                return
            } finally {
                attempts.remove(offer.session)
            }
            serve(connection)
        }

        /** The mailbox dropped: opened again after a while, longer each time, unless it's closed for good. */
        private fun onDown(reconnect: Boolean) {
            if (closed || !reconnect) return
            val wait = retryMillis
            retryMillis = (retryMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            try {
                retries.schedule({ threads.execute(::start) }, wait, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // Closed meanwhile.
            }
        }

        @Synchronized
        fun close() {
            closed = true
            mailbox?.close()
        }
    }

    /** A pair's mailbox at the Worker: sends signals sealed, and hands those that come, opened, to [onSignal]. */
    @Suppress("TooManyFunctions") // Half of them are the mailbox's callbacks.
    private inner class Mailbox(
        private val worker: URI,
        private val pair: String,
        secret: ByteArray,
        private val onSignal: (Signal) -> Unit,
        private val onDown: (reconnect: Boolean) -> Unit = {},
    ) : SignalingMailbox.Listener {
        private val codec = SignalCodec(SignalingCipher(secret, pair))
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val ready = CountDownLatch(1)

        /** Whether [onDown] was called, or needn't be: it's called once, and not after [close]. */
        private val reported = AtomicBoolean()

        @Volatile
        private var connection: SignalingMailbox.Connection? = null

        @Volatile
        private var peerOnline = false

        @Volatile
        private var down = false

        @Volatile
        var failure: Throwable? = null
            private set

        fun open() {
            connection = SignalingMailbox(worker, signer, okHttp).open(pair, this)
        }

        /** Whether the other phone is online; `null` if the mailbox didn't open in time. */
        fun awaitReady(timeoutMillis: Long): Boolean? =
            if (ready.await(timeoutMillis, TimeUnit.MILLISECONDS) && !down) peerOnline else null

        /** Whether the other phone came online within [timeoutMillis]. */
        fun awaitPeer(timeoutMillis: Long): Boolean = lock.withLock {
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!peerOnline && !down && left > 0) left = changed.awaitNanos(left)
            peerOnline
        }

        fun pipe(incoming: BlockingQueue<Signal>) = object : SignalPipe {
            override fun send(signal: Signal) {
                connection?.send(codec.seal(signal))
            }

            override val incoming = incoming
        }

        override fun onReady(peerOnline: Boolean, queued: Int) {
            update { this.peerOnline = peerOnline }
            ready.countDown()
        }

        override fun onPeer(online: Boolean) = update { peerOnline = online }

        override fun onMessage(message: ByteArray) {
            val signal = try {
                codec.open(message)
            } catch (e: IOException) {
                log("A signal that wasn't sealed for this pair was ignored", e)
                return
            }
            onSignal(signal)
        }

        override fun onError(code: String) = log("The rendezvous Worker refused a signal: $code", null)

        override fun onClosed(code: Int, reason: String) {
            stopped()
            // Replaced by a newer connection of this phone, or the pair is gone: nothing to come back to.
            report(reconnect = code != REPLACED && code != UNPAIRED)
        }

        override fun onFailure(error: Throwable) {
            failure = error
            stopped()
            if (!reported.get()) log("The rendezvous Worker's mailbox dropped", error)
            report(reconnect = true)
        }

        private fun stopped() {
            update { down = true }
            ready.countDown()
        }

        private fun report(reconnect: Boolean) {
            if (reported.compareAndSet(false, true)) onDown(reconnect)
        }

        private fun update(change: () -> Unit) = lock.withLock {
            change()
            changed.signalAll()
        }

        /** Closes it on purpose: [onDown] isn't called. */
        fun close() {
            reported.set(true)
            stopped()
            connection?.close()
        }

        /** Closes it as dropped: [onDown] is called. */
        fun drop() {
            stopped()
            connection?.close()
            report(reconnect = true)
        }
    }

    /**
     * The producer's connection: its mailbox closes with it. Meanwhile it reports what went through the relay
     * (PLAN §8.5): only the producer does, so the Worker counts each byte once.
     */
    private inner class InternetConnection(
        private val connection: PeerConnection,
        private val mailbox: Mailbox,
        private val client: RendezvousClient,
        private val pair: String,
    ) : PeerConnection by connection {
        /** The Worker tells sessions apart by this: 16 random bytes. */
        private val session = base64Url(ByteArray(SESSION_BYTES).also(SecureRandom()::nextBytes))
        private val reporting = REPORTS.scheduleWithFixedDelay(
            ::report,
            timeouts.relayReport,
            timeouts.relayReport,
            TimeUnit.MILLISECONDS,
        )

        @Volatile
        private var reported = 0L

        @Synchronized
        private fun report() {
            val relayed = (connection as? RelayCounter)?.relayedBytes() ?: return
            if (relayed <= reported) return // Nothing (more) through the relay.
            try {
                client.reportRelayUsage(pair, session, relayed)
                reported = relayed
            } catch (e: IOException) {
                log("Couldn't report the bytes relayed", e) // The Worker's analytics catch up anyway.
            }
        }

        override fun close() {
            reporting.cancel(false)
            try {
                report()
            } finally {
                try {
                    connection.close()
                } finally {
                    mailbox.close()
                }
            }
        }
    }

    private companion object {
        fun daemon(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }

        val REPORTS: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("mediasync-relay"))
        const val SESSION_BYTES = 16

        const val MILLIS_PER_SECOND = 1000L
        const val MAX_RETRY_MILLIS = 5 * 60_000L
        const val REPLACED = 4001
        const val UNPAIRED = 4003
        val CLIENT_ERRORS = 400..499
    }
}
