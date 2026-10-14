/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PairingClient
import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.ProducerSession
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.FakeSentLedger
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The whole stack: pairing, then syncing, over real TLS sockets. */
class LanSyncEndToEndTest {

    private val consumerIdentity = testIdentity("Pixel 2 XL")
    private val producerIdentity = testIdentity("Anna's Pixel")
    private val executor = Executors.newCachedThreadPool()
    private val token = "qr-one-time-tokn".encodeUtf8()

    // Consumer state: starts with no paired devices.
    private val registry = FakePeerRegistry()
    private val received = FakeReceivedLedger()
    private val sink = FakeMediaSink()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun consumerSession() = ConsumerSession(
        LocalDevice("Pixel 2 XL", "test"),
        registry,
        received,
        sink,
        FakePairingGate(token),
    )

    /** Admits unknown devices so they can pair, like the consumer's QR screen does. */
    private fun startConsumer(): Pair<LanServer, java.util.concurrent.Future<ServeResult>> {
        val server = LanServer(consumerIdentity, { true }, timeouts = LanTimeouts(readMillis = 10_000))
        val served = executor.submit(Callable { server.accept().handshake().use { consumerSession().serve(it) } })
        return server to served
    }

    private fun connect(server: LanServer, expected: DeviceId = consumerIdentity.id) = LanClient.connect(
        InetSocketAddress(InetAddress.getLoopbackAddress(), server.port),
        producerIdentity,
        expected,
    )

    @Test
    fun `pair by QR code, then sync photos over TLS`() {
        // 1. Pairing: the producer knows the consumer's id and token from the QR code.
        val (pairingServer, pairingServed) = startConsumer()
        val outcome = pairingServer.use { server ->
            connect(server).use { PairingClient(LocalDevice("Anna's Pixel", "test")).pair(it, token) }
        }
        outcome.decision shouldBe PairingDecision.ACCEPTED
        outcome.consumer shouldBe PairedPeer(consumerIdentity.id, "Pixel 2 XL", DeviceRole.CONSUMER)
        pairingServed.get(10, TimeUnit.SECONDS) shouldBe
            ServeResult.Paired(PairedPeer(producerIdentity.id, "Anna's Pixel", DeviceRole.PRODUCER))

        // 2. Syncing over a new connection.
        val photos = List(3) { FakeMedia("photo$it", Random(it).nextBytes(1_500_000 + it)) }
        val ledger = FakeSentLedger()
        val (syncServer, syncServed) = startConsumer()
        val result = syncServer.use { server ->
            connect(server).use {
                ProducerSession(LocalDevice("Anna's Pixel", "test"), FakeMediaSource(photos), ledger).sync(it)
            }
        }

        result shouldBe SyncResult(sent = 3, bytesSent = photos.sumOf { it.bytes.size.toLong() })
        (syncServed.get(10, TimeUnit.SECONDS) as ServeResult.Synced).received shouldBe 3
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        ledger.sentFingerprints(consumerIdentity.id).size shouldBe 3
    }
}
