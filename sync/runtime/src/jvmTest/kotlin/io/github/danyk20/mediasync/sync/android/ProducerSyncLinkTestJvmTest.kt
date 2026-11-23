/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.Faults
import io.github.danyk20.mediasync.sync.engine.testing.blockingScope
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test

/** The link test of [ProducerSync] over in-memory streams, with a consumer session answering on each one. */
class ProducerSyncLinkTestJvmTest {

    private val producer: DeviceId = deviceId(1)
    private val consumer: DeviceId = deviceId(2)
    private val receiver = Peer(
        id = consumer,
        name = "Receiver",
        role = DeviceRole.CONSUMER,
        pairedAtMillis = 0,
        lastAddress = null,
        lastSeenMillis = null,
        wifiAddresses = listOf("192.168.1.20:47420"),
    )

    /** The producer's end, reached at a Wi-Fi address (in-memory connections have none to parse). */
    private class AtAddress(private val inner: PeerConnection) : PeerConnection by inner {
        override val remoteAddress = "192.168.1.20:47420"
        var closed = false
            private set

        override fun close() {
            closed = true
            inner.close()
        }
    }

    /** A connection whose reads fail with an error that is not an IOException, as a broken probe's would. */
    private class BrokenReads(private val inner: PeerConnection) : PeerConnection by inner {
        override val source: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = error("Broken reader")

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()
    }

    private fun serve(pair: ConnectionPair, scope: CoroutineScope) = scope.launch {
        val session = ConsumerSession(
            LocalDevice("Receiver", "0.0.0"),
            FakePeerRegistry(PairedPeer(producer, "Sender", DeviceRole.PRODUCER)),
            FakeReceivedLedger(),
            FakeMediaSink(),
            FakePairingGate(ByteString.EMPTY),
        )
        // A stream the test cuts ends this session with an IOException: that is the point, not a failure.
        runCatching { session.serve(pair.consumerEnd) }
    }

    @Test
    fun aLinkTestOverTwoStreamsMeasuresBoth() {
        val scope = blockingScope()
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    val pairs = List(2) { ConnectionPair(producer, consumer) }
                    pairs.forEach { serve(it, scope) }
                    val sync = producerSync(
                        FakePeers(receiver),
                        FakeLanLinks(ArrayDeque(pairs.map { AtAddress(it.producerEnd) })),
                        this@runBlocking,
                    )

                    val speed = sync.linkTest(streams = 2, durationMillis = 300)

                    speed.shouldNotBeNull()
                    assertTrue(speed.bytes > 0)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aStreamThatFailsEndsTheLinkTestWithoutHanging() {
        val scope = blockingScope()
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    val first = ConnectionPair(producer, consumer)
                    // The second stream is cut before it carries anything: its link test fails at once.
                    val second =
                        ConnectionPair(producer, consumer, faults = Faults(cutAfter = 0), timeoutMillis = 2_000)
                    serve(first, scope)
                    val ends = listOf(AtAddress(first.producerEnd), AtAddress(second.producerEnd))
                    val sync = producerSync(FakePeers(receiver), FakeLanLinks(ArrayDeque(ends)), this@runBlocking)

                    sync.linkTest(streams = 2, durationMillis = 300).shouldBeNull()
                    ends.forEach { assertTrue(it.closed) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aProbeThatBreaksWithAnUnexpectedErrorIsNoResult() {
        val scope = blockingScope()
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    val pair = ConnectionPair(producer, consumer)
                    serve(pair, scope)
                    val end = AtAddress(BrokenReads(pair.producerEnd))
                    val sync =
                        producerSync(FakePeers(receiver), FakeLanLinks(ArrayDeque(listOf(end))), this@runBlocking)

                    sync.linkTest(streams = 1, durationMillis = 300).shouldBeNull()
                    assertTrue(end.closed)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingTheLinkTestClosesItsConnections() {
        val scope = blockingScope()
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    val pairs = List(2) { ConnectionPair(producer, consumer) }
                    pairs.forEach { serve(it, scope) }
                    val ends = pairs.map { AtAddress(it.producerEnd) }
                    val sync = producerSync(FakePeers(receiver), FakeLanLinks(ArrayDeque(ends)), this@runBlocking)

                    val probe = launch { sync.linkTest(streams = 2, durationMillis = LONG_PROBE_MILLIS) }
                    delay(CANCEL_AFTER_MILLIS) // Both streams are open and sending by now.
                    val started = System.currentTimeMillis()
                    probe.cancelAndJoin()

                    assertTrue(System.currentTimeMillis() - started < LONG_PROBE_MILLIS / 6)
                    ends.forEach { assertTrue(it.closed) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val LONG_PROBE_MILLIS = 60_000L
        const val CANCEL_AFTER_MILLIS = 500L
        const val TEST_TIMEOUT_MILLIS = 30_000L
    }
}
