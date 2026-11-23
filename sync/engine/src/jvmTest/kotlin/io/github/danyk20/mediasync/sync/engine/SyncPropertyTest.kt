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
import io.github.danyk20.mediasync.sync.engine.testing.Faults
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

/**
 * The guarantee that matters most: however the network fails, repeated syncs end with every photo stored
 * exactly once and byte-identical, and nothing corrupt is ever stored.
 */
class SyncPropertyTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Phone", "test")
    private val iterations = 40

    private val fileSizes = Arb.list(Arb.int(0..(5 * MIB / 2)), 1..6)

    @Test
    fun `dropped connections never lose or duplicate a file`() = runBlocking {
        checkAll(iterations, fileSizes, Arb.list(Arb.long(0L..8L * MIB), 0..4)) { sizes, cuts ->
            val world = World(sizes)

            cuts.forEach { cut -> world.sync(Faults(cutAfter = cut)) }
            world.sync().getOrThrow()

            world.assertEverythingStoredExactlyOnce()
        }
        Unit
    }

    @Test
    fun `corruption in transit is never stored`() = runBlocking {
        checkAll(iterations, fileSizes, Arb.list(Arb.long(0L..6L * MIB), 0..2)) { sizes, flips ->
            val world = World(sizes)

            flips.forEach { flip ->
                world.sync(Faults(flipByteAt = flip))
                world.originals shouldContainAll world.consumer.storedContents()
            }
            world.sync().getOrThrow()

            world.assertEverythingStoredExactlyOnce()
        }
        Unit
    }

    private inner class World(sizes: List<Int>) {
        private val media = sizes.mapIndexed { i, size -> FakeMedia("item$i", Random(i * 7919 + size).nextBytes(size)) }
            .distinctBy { it.descriptor.fingerprint }
        val originals = media.map { it.bytes.toByteString() }.toSet()
        private val source = FakeMediaSource(media)
        private val sentLedger = FakeSentLedger()
        private val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER))
        private val receivedLedger = FakeReceivedLedger()
        val consumer = FakeMediaSink()

        fun sync(faults: Faults = Faults()): Result<SyncResult> {
            // Short timeouts: a corrupted frame header can stall a session until the read times out.
            val connection = ConnectionPair(producerId, consumerId, faults, timeoutMillis = 500)
            return runBothSides(
                producer = { ProducerSession(local, source, sentLedger).sync(connection.producerEnd) },
                consumer = {
                    ConsumerSession(local, registry, receivedLedger, consumer, FakePairingGate(okio.ByteString.EMPTY))
                        .serve(connection.consumerEnd)
                },
            ).first
        }

        fun assertEverythingStoredExactlyOnce() {
            consumer.stored.size shouldBe originals.size
            consumer.storedContents() shouldBe originals
            consumer.partials.shouldBeEmpty()
            receivedLedger.items.size shouldBe originals.size
            sentLedger.sentFingerprints(consumerId) shouldBe media.map { it.descriptor.fingerprint }.toSet()
        }
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
