/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.transport.PeerConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The two phones find each other through a Worker, faked here, and connect through links, faked too. */
class InternetPathTest {

    private val consumer = RendezvousSigner(byteArrayOf(1, 1), { it.copyOf(SIGNATURE_BYTES) })
    private val producer = RendezvousSigner(byteArrayOf(2, 2), { it.copyOf(SIGNATURE_BYTES) })
    private val secret = ByteArray(32) { 7 }
    private val worker = FakeWorker()
    private val server = MockWebServer().apply {
        dispatcher = worker
        start()
    }
    private val url = URI(server.url("/").toString())
    private val links = FakeLinks(producer.deviceId, consumer.deviceId)
    private val timeouts =
        InternetPath.Timeouts(ready = WAIT, wake = WAIT, connect = WAIT, retry = 50, relayReport = 50)
    private val served = LinkedBlockingQueue<PeerConnection>()
    private val listening = mutableListOf<Closeable>()

    private fun listen() = InternetPath(consumer, links, timeouts = timeouts)
        .listen(url, mapOf(producer.deviceId to secret)) { served.put(it) }
        .also { listening += it }

    private fun connect() = InternetPath(producer, links, timeouts = timeouts).connect(consumer.deviceId, url, secret)

    @AfterEach
    fun tearDown() {
        listening.forEach(Closeable::close)
        server.close()
    }

    @Test
    fun theSendingPhoneReachesTheReceivingPhoneThroughItsWorker() {
        listen()
        worker.awaitOnline(pairId(consumer.deviceId, producer.deviceId))

        connect().use { toConsumer ->
            val atConsumer = served.poll(WAIT, TimeUnit.MILLISECONDS)!!
            toConsumer.sink.writeUtf8("hello").flush()

            atConsumer.source.readUtf8(5) shouldBe "hello"
            toConsumer.peerId shouldBe consumer.deviceId
            atConsumer.peerId shouldBe producer.deviceId
        }
        worker.registered shouldBe setOf(pairId(consumer.deviceId, producer.deviceId))
        // Offer, candidate, answer: the Worker relayed them, and could read none.
        worker.relayed.size shouldBe 3
        worker.relayed.none { "v=0" in it.string(Charsets.ISO_8859_1) } shouldBe true
        links.candidates.get() shouldBe 1
    }

    @Test
    fun theSendingPhoneReportsWhatWentThroughTheRelay() {
        listen()
        worker.awaitOnline(pairId(consumer.deviceId, producer.deviceId))
        val connection = connect()
        served.poll(WAIT, TimeUnit.MILLISECONDS)!!.close()

        links.relayed.set(1_000_000)
        val end = System.currentTimeMillis() + WAIT
        while (worker.relayReports.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        links.relayed.set(3_000_000)
        connection.close()

        val reports = worker.relayReports.map { Json.parseToJsonElement(it).jsonObject }
        reports.map { it["relayedBytes"]?.jsonPrimitive?.long } shouldBe listOf(1_000_000L, 3_000_000L)
        reports.map { it["session"]?.jsonPrimitive?.content }.distinct().single()!!.length shouldBe 22
    }

    @Test
    fun directConnectionsAreNotReported() {
        listen()
        worker.awaitOnline(pairId(consumer.deviceId, producer.deviceId))

        connect().close()

        worker.relayReports shouldBe emptyList()
    }

    @Test
    fun aReceivingPhoneThatIsntOnlineIsWokenAndWaitedFor() {
        worker.registered += pairId(consumer.deviceId, producer.deviceId) // When it last listened.
        worker.onWake = { listen() }

        connect().use { toConsumer ->
            served.poll(WAIT, TimeUnit.MILLISECONDS)!!.peerId shouldBe producer.deviceId
            toConsumer.peerId shouldBe consumer.deviceId
        }
        worker.wakes.get() shouldBe 1
    }

    @Test
    fun aReceivingPhoneThatCantBeWokenIsReportedOffline() {
        worker.registered += pairId(consumer.deviceId, producer.deviceId)
        worker.wakeRefusal = "no_push_token"

        val failure = shouldThrow<IOException> { connect() }

        failure.message shouldContain "couldn't be woken (no_push_token)"
    }

    @Test
    fun aPairTheReceivingPhoneNeverRegisteredIsRefused() {
        val failure = shouldThrow<RendezvousException> { connect() }

        failure.code shouldBe "unpaired"
    }

    @Test
    fun theReceivingPhoneOpensItsMailboxAgainAfterItDropped() {
        listen()
        val pair = pairId(consumer.deviceId, producer.deviceId)
        worker.awaitOnline(pair)

        worker.dropAll()
        worker.awaitOnline(pair)

        connect().use { served.poll(WAIT, TimeUnit.MILLISECONDS)!!.peerId shouldBe producer.deviceId }
        worker.mailboxesOpened.get() shouldBe 3 // The consumer's twice, the producer's once.
    }

    /** Connects the two phones through in-memory pipes once the offer and its answer went through the Worker. */
    private class FakeLinks(private val producer: DeviceId, private val consumer: DeviceId) : PeerLinks {
        private val pairs = ConcurrentHashMap<String, ConnectionPair>()
        val candidates = AtomicInteger()

        /** What went through the relay so far, as the producer's connection counts it. */
        val relayed = AtomicLong()

        override fun connect(
            peer: DeviceId,
            iceServers: List<IceServer>,
            pipe: SignalPipe,
            timeoutMillis: Long,
        ): PeerConnection {
            iceServers.single().urls shouldBe listOf("stun:stun.example")
            val session = UUID.randomUUID().toString()
            pipe.send(Signal.Offer(session, "v=0 from the producer"))
            pipe.send(Signal.Candidate(session, "0", 0, "candidate:1 1 udp 1 192.0.2.1 9 typ host"))
            while (true) {
                val signal = pipe.incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw IOException("No answer")
                if (signal is Signal.Answer && signal.session == session) {
                    val end = pairs.getValue(session).producerEnd
                    return object : PeerConnection by end, RelayCounter {
                        override fun relayedBytes() = relayed.get()
                    }
                }
            }
        }

        override fun accept(
            peer: DeviceId,
            offer: Signal.Offer,
            iceServers: List<IceServer>,
            pipe: SignalPipe,
            timeoutMillis: Long,
        ): PeerConnection {
            peer shouldBe producer
            val candidate = pipe.incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            if (candidate is Signal.Candidate && candidate.session == offer.session) candidates.incrementAndGet()
            val pair = ConnectionPair(producer, consumer)
            pairs[offer.session] = pair
            pipe.send(Signal.Answer(offer.session, "v=0 from the consumer"))
            return pair.consumerEnd
        }
    }

    private companion object {
        const val WAIT = 5_000L
        const val SIGNATURE_BYTES = 8
    }
}
