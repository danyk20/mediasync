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
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

class LinkTestTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")
    private val sink = FakeMediaSink()

    private fun consumer(paired: Boolean) = ConsumerSession(
        local,
        if (paired) FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER)) else FakePeerRegistry(),
        FakeReceivedLedger(),
        sink,
        FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
    )

    @Test
    fun aPairedPhoneMeasuresHowFastItCanSendAndNothingIsStored() {
        val connection = ConnectionPair(producerId, consumerId)

        val (speed, served) = runBothSides(
            producer = { LinkTest(local, durationMillis = 300).run(connection.producerEnd) },
            consumer = { consumer(paired = true).serve(connection.consumerEnd) },
        )

        val measured = speed.getOrThrow()
        measured.bytes shouldBeGreaterThan 0
        measured.megabytesPerSecond shouldBeGreaterThan 0.0
        served.getOrThrow() shouldBe ServeResult.LinkTested(measured.bytes, measured.micros)
        sink.stored.size shouldBe 0
    }

    @Test
    fun anUnpairedPhoneCantMeasure() {
        val connection = ConnectionPair(producerId, consumerId)

        val (speed, served) = runBothSides(
            producer = { LinkTest(local, durationMillis = 100).run(connection.producerEnd) },
            consumer = { consumer(paired = false).serve(connection.consumerEnd) },
        )

        speed.exceptionOrNull().shouldBeInstanceOf<PeerEndedSessionException>().byeReason shouldBe Bye.Reason.NOT_PAIRED
        served.getOrThrow() shouldBe ServeResult.Rejected(Bye.Reason.NOT_PAIRED)
    }

    @Test
    fun parallelConnectionsAddUp() {
        val one = LinkSpeed(bytes = 100 * MB, micros = 2_000_000)

        (one + LinkSpeed(bytes = 50 * MB, micros = 2_500_000)) shouldBe LinkSpeed(150 * MB, 2_500_000)
        one.megabytesPerSecond shouldBe 50.0
        LinkSpeed(0, 0).megabytesPerSecond shouldBe 0.0
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
