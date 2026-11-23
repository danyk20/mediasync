/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
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
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

/** Files are sent back to back; confirmations come in while the next ones go (docs/PLAN.md §7). */
class PipeliningTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")
    private val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER))
    private val received = FakeReceivedLedger()
    private val sink = FakeMediaSink()
    private val sent = FakeSentLedger()

    private fun photos(count: Int, size: Int) = List(count) { FakeMedia("p$it", Random(it).nextBytes(size)) }

    private fun sync(
        source: MediaSource,
        window: Int = 16,
        faults: Faults = Faults(),
        latencyMillis: Long = 0,
        session: ProducerSession = ProducerSession(local, source, sent, window = window),
    ): Result<SyncResult> {
        val connection = ConnectionPair(producerId, consumerId, faults, latencyMillis = latencyMillis)
        return runBothSides(
            producer = { session.sync(connection.producerEnd) },
            consumer = {
                ConsumerSession(local, registry, received, sink, FakePairingGate(TOKEN)).serve(connection.consumerEnd)
            },
        ).first
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    @Test
    fun manySmallPhotosDontWaitForEachConfirmation() {
        val photos = photos(count = 40, size = 50_000)

        val oneByOne = timed {
            sync(FakeMediaSource(photos), window = 1, latencyMillis = 25).getOrThrow().sent shouldBe 40
        }
        sink.stored.clear()
        received.items.clear()
        sent.entries.clear()
        val pipelined = timed {
            sync(FakeMediaSource(photos), latencyMillis = 25).getOrThrow().sent shouldBe 40
        }

        // One by one, each photo waits a 50 ms round trip: at least 2 s in all.
        pipelined shouldBeLessThan oneByOne / 3
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
    }

    @Test
    fun aCorruptFileAmongOthersFailsAloneAndTheRestArrive() {
        val photos = photos(count = 5, size = 100_000)

        // Lands in the third file, after the offer and two files.
        val result = sync(FakeMediaSource(photos), faults = Faults(flipByteAt = 250_000)).getOrThrow()

        result.sent shouldBe 4
        result.failed shouldBe 1
        sink.stored.size shouldBe 4
        sent.entries.size shouldBe 4
    }

    @Test
    fun aDroppedConnectionWithFilesInFlightResumesWithoutResendingConfirmedOnes() {
        val photos = photos(count = 30, size = 200_000)
        val source = FakeMediaSource(photos)

        sync(source, faults = Faults(cutAfter = 3_000_000)).isFailure shouldBe true
        val confirmed = sent.entries.size
        confirmed shouldBeLessThan 30

        val second = sync(source).getOrThrow()

        // A file stored just before the drop, whose confirmation was lost with it, is recognised, not sent again.
        second.sent + second.alreadyOnConsumer shouldBe 30 - confirmed
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        received.items.size shouldBe 30
    }

    @Test
    fun aConfirmationStillBeingRecordedCountsBeforeTheSessionEnds() {
        val source = FakeMediaSource(photos(count = 1, size = 10_000))
        val recording = CountDownLatch(1)
        val slowLedger = object : SentLedger by sent {
            override fun markSent(consumer: DeviceId, fingerprint: ByteString, contentId: ByteString) {
                recording.countDown()
                Thread.sleep(SLOW_RECORD_MILLIS) // A database write on a busy phone.
                sent.markSent(consumer, fingerprint, contentId)
            }
        }
        // Once the photo went, the session checks whether it's done only while its confirmation is being recorded.
        val afterTheConfirmationCame = {
            synchronized(source.opened) { source.opened.isEmpty() } || recording.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        val session = ProducerSession(local, source, slowLedger, shouldContinue = afterTheConfirmationCame)
        val result = sync(source, session = session).getOrThrow()

        result.sent shouldBe 1
        sent.entries.size shouldBe 1
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val SLOW_RECORD_MILLIS = 500L
        const val WAIT_SECONDS = 5L
        val TOKEN = "one-time-token!!".encodeToByteArray().toByteString()
    }
}
