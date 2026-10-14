/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.FakeSentLedger
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

class PairingTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val token = "0123456789abcdef".encodeUtf8()

    // The consumer phone: what it knows survives across sessions.
    private val registry = FakePeerRegistry()
    private val received = FakeReceivedLedger()
    private val sink = FakeMediaSink()

    private fun pair(
        gate: FakePairingGate,
        presentedToken: okio.ByteString = token,
    ): Pair<PairingOutcome, ServeResult> {
        val connection = ConnectionPair(producerId, consumerId)
        val (producer, consumer) = runBothSides(
            producer = {
                PairingClient(LocalDevice("Anna's Pixel", "1.0")).pair(connection.producerEnd, presentedToken)
            },
            consumer = { consumerSession(gate).serve(connection.consumerEnd) },
        )
        return producer.getOrThrow() to consumer.getOrThrow()
    }

    /** One sync from the producer's [library]; its [ledger] survives across sessions, like on the phone. */
    private fun sync(library: FakeMediaSource, ledger: FakeSentLedger): Pair<SyncResult, ServeResult> {
        val connection = ConnectionPair(producerId, consumerId)
        val (producer, consumer) = runBothSides(
            producer = {
                ProducerSession(LocalDevice("Anna's Pixel", "1.0"), library, ledger).sync(connection.producerEnd)
            },
            consumer = { consumerSession(FakePairingGate(token)).serve(connection.consumerEnd) },
        )
        return producer.getOrThrow() to consumer.getOrThrow()
    }

    private fun consumerSession(gate: PairingGate) =
        ConsumerSession(LocalDevice("Pixel 2 XL", "1.0"), registry, received, sink, gate)

    @Test
    fun `a valid token and the user's approval pair both phones`() {
        val gate = FakePairingGate(token)

        val (producer, consumer) = pair(gate)

        producer shouldBe PairingOutcome(
            PairingDecision.ACCEPTED,
            PairedPeer(consumerId, "Pixel 2 XL", DeviceRole.CONSUMER),
        )
        consumer shouldBe ServeResult.Paired(PairedPeer(producerId, "Anna's Pixel", DeviceRole.PRODUCER))
        registry.isPaired(producerId) shouldBe true
        gate.requests.single() shouldBe PairingRequest(producerId, "Anna's Pixel", token)
    }

    @Test
    fun `a wrong token is refused`() {
        val (producer, consumer) = pair(FakePairingGate(token), presentedToken = "forged".encodeUtf8())

        producer shouldBe PairingOutcome(PairingDecision.INVALID_TOKEN, consumer = null)
        consumer shouldBe ServeResult.PairingRefused(PairingDecision.INVALID_TOKEN)
        registry.peers.shouldBeEmpty()
    }

    @Test
    fun `the consumer's user can decline`() {
        val (producer, _) = pair(FakePairingGate(token, userAccepts = false))

        producer.decision shouldBe PairingDecision.DECLINED
        registry.peers.shouldBeEmpty()
    }

    @Test
    fun `a paired producer that scans the QR code again is paired again and keeps syncing`() {
        val first = FakeMedia("first", "first photo".encodeToByteArray())
        val library = FakeMediaSource(listOf(first))
        val ledger = FakeSentLedger()
        registry.register(PairedPeer(producerId, "Old name", DeviceRole.PRODUCER))
        sync(library, ledger).first.sent shouldBe 1
        val gate = FakePairingGate(token)

        val (producer, consumer) = pair(gate)

        producer shouldBe PairingOutcome(
            PairingDecision.ACCEPTED,
            PairedPeer(consumerId, "Pixel 2 XL", DeviceRole.CONSUMER),
        )
        consumer shouldBe ServeResult.Paired(PairedPeer(producerId, "Anna's Pixel", DeviceRole.PRODUCER))
        gate.requests.single() shouldBe PairingRequest(producerId, "Anna's Pixel", token)
        registry.peers.values.single() shouldBe PairedPeer(producerId, "Anna's Pixel", DeviceRole.PRODUCER)

        val second = FakeMedia("second", "second photo".encodeToByteArray())
        library.put(second)
        val (sent, served) = sync(library, ledger)

        sent shouldBe SyncResult(sent = 1, bytesSent = second.bytes.size.toLong())
        served shouldBe ServeResult.Synced(1, 0, 0, second.bytes.size.toLong())
        received.items.map { it.descriptor } shouldContainExactlyInAnyOrder listOf(first.descriptor, second.descriptor)
        sink.storedContents() shouldBe setOf(first.bytes.toByteString(), second.bytes.toByteString())
    }

    @Test
    fun `a paired producer with a wrong token is refused and stays paired`() {
        val paired = PairedPeer(producerId, "Anna's Pixel", DeviceRole.PRODUCER)
        registry.register(paired)

        val (producer, consumer) = pair(FakePairingGate(token), presentedToken = "forged".encodeUtf8())

        producer shouldBe PairingOutcome(PairingDecision.INVALID_TOKEN, consumer = null)
        consumer shouldBe ServeResult.PairingRefused(PairingDecision.INVALID_TOKEN)
        registry.peers.values.single() shouldBe paired
        val photo = FakeMedia("photo", "photo".encodeToByteArray())
        sync(FakeMediaSource(listOf(photo)), FakeSentLedger()).first.sent shouldBe 1
    }
}
