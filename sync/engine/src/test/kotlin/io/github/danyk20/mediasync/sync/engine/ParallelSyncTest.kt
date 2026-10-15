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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Several connections at once, sharing the items (docs/PLAN.md §7). */
class ParallelSyncTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")
    private val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER))
    private val received = FakeReceivedLedger()
    private val sent = FakeSentLedger()
    private val consumers = Executors.newCachedThreadPool()
    private val connections = AtomicInteger()
    private var sink: MediaSink = FakeMediaSink()
    private val space by lazy { SpaceBudget(sink) }

    @AfterEach
    fun tearDown() {
        consumers.shutdownNow()
        consumers.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun photos(count: Int, size: Int) = List(count) { FakeMedia("p$it", Random(it).nextBytes(size)) }

    /** A connection to the consumer, which serves it on its own thread, as the app does. */
    private fun connection(latencyMillis: Long) = ConnectionPair(producerId, consumerId, latencyMillis = latencyMillis)
        .also { pair ->
            connections.incrementAndGet()
            consumers.submit {
                ConsumerSession(local, registry, received, sink, FakePairingGate(TOKEN), space = space)
                    .serve(pair.consumerEnd)
            }
        }.producerEnd

    private fun parallel(
        photos: List<FakeMedia>,
        latencyMillis: Long = 0,
        mayAddStream: () -> Boolean = { true },
        holdBack: (SourceItem) -> Boolean = { false },
        progress: ProgressListener = ProgressListener {},
    ): SyncResult {
        val source = FakeMediaSource(photos)
        return ParallelSync(
            session = { listener -> ProducerSession(local, source, sent, listener, window = 4) },
            connect = { connection(latencyMillis) },
            progress = progress,
            mayAddStream = mayAddStream,
            probeMillis = 150,
            holdBack = holdBack,
        ).sync(connection(latencyMillis))
    }

    @Test
    fun aBigBacklogOverSlowLinksUsesMoreConnectionsAndSendsEachPhotoOnce() {
        val photos = photos(count = 200, size = 50_000)
        val last = mutableListOf<TransferProgress>()

        val result = parallel(photos, latencyMillis = 15) { synchronized(last) { last += it } }

        connections.get() shouldBeGreaterThan 1
        connections.get() shouldBeLessThanOrEqual 4
        result.sent shouldBe 200
        received.items.size shouldBe 200
        (sink as FakeMediaSink).storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        synchronized(last) { last.maxOf { it.itemsDone } } shouldBe 200
        last.first().itemsTotal shouldBe 200
    }

    @Test
    fun whatsHeldBackIsCountedNotSentAndGoesNextTime() {
        val photos = photos(count = 6, size = 10_000)
        val videos = List(3) { FakeMedia("v$it", Random(100 + it).nextBytes(20_000), "v$it.mp4", "video/mp4") }
        val isVideo = { item: SourceItem -> item.descriptor.mimeType.startsWith("video/") }

        val overRelay = parallel(photos + videos, holdBack = isVideo)

        overRelay.sent shouldBe 6
        overRelay.heldBack shouldBe 3
        received.items.none { it.descriptor.mimeType == "video/mp4" } shouldBe true
        val overWifi = parallel(photos + videos)
        overWifi.sent shouldBe 3
        overWifi.heldBack shouldBe 0
    }

    @Test
    fun aWarmPhoneKeepsToOneConnection() {
        val result = parallel(photos(count = 100, size = 50_000), latencyMillis = 15, mayAddStream = { false })

        connections.get() shouldBe 1
        result.sent shouldBe 100
    }

    @Test
    fun togetherTheyNeverTakeMoreSpaceThanThereIs() {
        val fake = FakeMediaSink()
        // Free space shrinks as files are stored, like a real phone's.
        sink = object : MediaSink by fake {
            override fun availableBytes() = CAPACITY - fake.stored.values.sumOf { it.size.toLong() }
        }

        val result = parallel(photos(count = 40, size = 100_000), latencyMillis = 15)

        fake.stored.values.sumOf { it.size.toLong() } shouldBeLessThanOrEqual CAPACITY
        result.sent shouldBe fake.stored.size
        result.deferred shouldBeGreaterThan 0
    }

    @Test
    fun aBatchEndsAtItsSizeSoBigFilesAreLeftForOtherConnections() {
        val queue = WorkQueue(photos(count = 5, size = 10).map { it.item.sized(40) })

        queue.take(maxItems = 64, maxBytes = 100).size shouldBe 3 // 40 + 40 + 40: the third reaches 100.
        queue.take(maxItems = 64, maxBytes = 10).size shouldBe 1 // Bigger than a batch: alone.
        queue.take(maxItems = 64, maxBytes = 100).size shouldBe 1
        queue.isEmpty() shouldBe true
        queue.totalBytes shouldBe 200
    }

    private fun SourceItem.sized(size: Long) = copy(descriptor = descriptor.copy(size = size))

    @Test
    fun aBudgetReservesAndReleases() {
        val budget = SpaceBudget(FakeMediaSink(availableBytes = 1_000))

        budget.tryReserve(600) shouldBe true
        budget.tryReserve(600) shouldBe false
        budget.release(600)
        budget.tryReserve(1_000) shouldBe true
        budget.release(5_000) // More than reserved: never below nothing.
        budget.tryReserve(1_000) shouldBe true
    }

    private companion object {
        const val CAPACITY = 1_000_000L
        val TOKEN = "one-time-token!!".encodeToByteArray().toByteString()
    }
}
