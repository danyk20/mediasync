/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test

class PairingTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val token = "0123456789abcdef".encodeUtf8()
    private val registry = FakePeerRegistry()

    private fun pair(
        gate: FakePairingGate,
        presentedToken: okio.ByteString = token,
    ): Pair<PairingOutcome, ServeResult> {
        val connection = ConnectionPair(producerId, consumerId)
        val (producer, consumer) = runBothSides(
            producer = {
                PairingClient(LocalDevice("Anna's Pixel", "1.0")).pair(connection.producerEnd, presentedToken)
            },
            consumer = {
                ConsumerSession(LocalDevice("Pixel 2 XL", "1.0"), registry, FakeReceivedLedger(), FakeMediaSink(), gate)
                    .serve(connection.consumerEnd)
            },
        )
        return producer.getOrThrow() to consumer.getOrThrow()
    }

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
}
