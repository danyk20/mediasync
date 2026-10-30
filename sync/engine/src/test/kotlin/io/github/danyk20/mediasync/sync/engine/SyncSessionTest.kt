/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SendingOrder
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
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

class SyncSessionTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")

    /** One producer phone: its library and its ledger survive across sessions, like on a real device. */
    private inner class Producer(media: List<FakeMedia>) {
        val source = FakeMediaSource(media)
        val ledger = FakeSentLedger()
    }

    /** The consumer phone, with the producer already paired. */
    private inner class Consumer(sink: FakeMediaSink = FakeMediaSink()) {
        val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER))
        val ledger = FakeReceivedLedger()
        val sink = sink
    }

    private fun sync(
        producer: Producer,
        consumer: Consumer,
        faults: Faults = Faults(),
        from: io.github.danyk20.mediasync.core.model.DeviceId = producerId,
    ): Pair<Result<SyncResult>, Result<ServeResult>> {
        val connection = ConnectionPair(from, consumerId, faults)
        return runBothSides(
            producer = { ProducerSession(local, producer.source, producer.ledger).sync(connection.producerEnd) },
            consumer = {
                ConsumerSession(local, consumer.registry, consumer.ledger, consumer.sink, FakePairingGate(TOKEN))
                    .serve(connection.consumerEnd)
            },
        )
    }

    @Test
    fun `sends every item once, byte for byte, with its metadata`() {
        val media = listOf(media("a", 3 * MIB + 17), media("b", 10), media("empty", 0), media("c", MIB))
        val producer = Producer(media)
        val consumer = Consumer()

        val (sent, served) = sync(producer, consumer)

        sent.getOrThrow() shouldBe SyncResult(sent = 4, bytesSent = media.sumOf { it.bytes.size.toLong() })
        served.getOrThrow() shouldBe ServeResult.Synced(4, 0, 0, media.sumOf { it.bytes.size.toLong() })
        consumer.sink.storedContents() shouldBe media.map { it.bytes.toByteString() }.toSet()
        consumer.ledger.items.map { it.descriptor } shouldContainExactlyInAnyOrder media.map { it.descriptor }
        producer.ledger.sentFingerprints(consumerId) shouldBe media.map { it.descriptor.fingerprint }.toSet()
    }

    @Test
    fun `a second sync sends nothing`() {
        val producer = Producer(listOf(media("a", 1000), media("b", 2000)))
        val consumer = Consumer()
        sync(producer, consumer)
        producer.source.opened.clear()

        val (sent, served) = sync(producer, consumer)

        sent.getOrThrow() shouldBe SyncResult()
        served.getOrThrow() shouldBe ServeResult.Synced(received = 0, alreadyHad = 0, failed = 0, bytesReceived = 0)
        producer.source.opened.shouldBeEmpty()
        consumer.sink.stored.size shouldBe 2
    }

    @Test
    fun `shared photos go first, then the newest`() {
        val old = FakeMedia("old", byteArrayOf(1), meta = MediaMeta(capturedAtMillis = 1_000))
        val new = FakeMedia("new", byteArrayOf(2), meta = MediaMeta(capturedAtMillis = 3_000))
        val shared = FakeMedia("shared", byteArrayOf(3), meta = MediaMeta(capturedAtMillis = 2_000), shared = true)
        // The same photo, both in a synced folder and shared: it goes once, as shared.
        val sharedToo =
            FakeMedia("new-shared", byteArrayOf(2), meta = MediaMeta(capturedAtMillis = 3_000), shared = true)
        val producer = Producer(listOf(old, new, shared, sharedToo))

        val pending = ProducerSession(local, producer.source, producer.ledger).pendingFor(consumerId)

        pending.map { it.key } shouldBe listOf("new-shared", "shared", "old")
    }

    @Test
    fun `each sending order sorts what's waiting, after what was shared`() {
        fun media(key: String, takenAt: Long, video: Boolean = false, shared: Boolean = false) = FakeMedia(
            key,
            key.encodeToByteArray(),
            mimeType = if (video) "video/mp4" else "image/jpeg",
            meta = MediaMeta(capturedAtMillis = takenAt),
            shared = shared,
        )
        // A screenshot has no capture date: its file's date counts.
        val screenshot =
            FakeMedia("screenshot", byteArrayOf(9), meta = MediaMeta(capturedAtMillis = null, modifiedAtMillis = 2_500))
        val all = listOf(
            media("photo-1", 1_000),
            media("video-2", 2_000, video = true),
            screenshot,
            media("photo-3", 3_000),
            media("video-4", 4_000, video = true),
            media("shared-video-0", 0, video = true, shared = true),
        )
        fun order(order: SendingOrder) =
            ProducerSession(local, Producer(all).source, FakeSentLedger(), order = order).pendingFor(consumerId)
                .map { it.key }

        order(SendingOrder.NEWEST_FIRST) shouldBe
            listOf("shared-video-0", "video-4", "photo-3", "screenshot", "video-2", "photo-1")
        order(SendingOrder.AUTOMATIC) shouldBe order(SendingOrder.NEWEST_FIRST) // Unless resolved otherwise.
        order(SendingOrder.OLDEST_FIRST) shouldBe
            listOf("shared-video-0", "photo-1", "video-2", "screenshot", "photo-3", "video-4")
        order(SendingOrder.PHOTOS_FIRST) shouldBe
            listOf("shared-video-0", "photo-3", "screenshot", "photo-1", "video-4", "video-2")
        order(SendingOrder.VIDEOS_FIRST) shouldBe
            listOf("shared-video-0", "video-4", "video-2", "photo-3", "screenshot", "photo-1")
    }

    @Test
    fun `automatically, photos go first over the internet and the newest first elsewhere`() {
        SendingOrder.AUTOMATIC.resolved(overInternet = true) shouldBe SendingOrder.PHOTOS_FIRST
        SendingOrder.AUTOMATIC.resolved(overInternet = false) shouldBe SendingOrder.NEWEST_FIRST
        SendingOrder.entries.filter { it != SendingOrder.AUTOMATIC }.forEach { chosen ->
            chosen.resolved(overInternet = true) shouldBe chosen
        }
    }

    @Test
    fun `the source hears about everything the consumer confirmed`() {
        val sent = FakeMedia("sent", byteArrayOf(4), shared = true)
        val there = FakeMedia("there", byteArrayOf(5))
        val producer = Producer(listOf(sent))
        val consumer = Consumer()
        sync(producer, consumer).first.getOrThrow()
        // Already on the consumer from another phone: confirmed without sending.
        val other = Producer(listOf(there, FakeMedia("sent-again", byteArrayOf(4), shared = true)))
        sync(
            other,
            consumer.also {
                it.ledger.record(ReceivedItem(producerId, there.descriptor, ByteString.EMPTY, "x"))
            },
        )

        producer.source.confirmed shouldBe listOf("sent")
        other.source.confirmed shouldContainExactlyInAnyOrder listOf("there", "sent-again")
    }

    @Test
    fun `only new photos are sent after the first sync`() {
        val producer = Producer(listOf(media("a", 1000)))
        val consumer = Consumer()
        sync(producer, consumer)
        producer.source.put(media("b", 2000))

        val (sent, _) = sync(producer, consumer)

        sent.getOrThrow().sent shouldBe 1
        consumer.sink.stored.size shouldBe 2
    }

    @Test
    fun `nothing is resent after the consumer's files were deleted by the connector app`() {
        val producer = Producer(listOf(media("a", 1000)))
        val consumer = Consumer()
        sync(producer, consumer)
        consumer.sink.stored.clear() // Google Photos freed the space.

        val (sent, _) = sync(producer, consumer)

        sent.getOrThrow() shouldBe SyncResult()
    }

    @Test
    fun `a reinstalled producer rebuilds its ledger without transferring anything`() {
        val media = listOf(media("a", 5000), media("b", 7000))
        val consumer = Consumer()
        sync(Producer(media), consumer)
        val reinstalled = Producer(media) // Same photos, empty ledger.

        val (sent, _) = sync(reinstalled, consumer)

        sent.getOrThrow() shouldBe SyncResult(alreadyOnConsumer = 2)
        reinstalled.source.opened.shouldBeEmpty()
        reinstalled.ledger.sentFingerprints(consumerId).size shouldBe 2
        consumer.sink.stored.size shouldBe 2
    }

    @Test
    fun `the same picture on two phones is stored once`() {
        val shared = media("shared", 4000)
        val consumer = Consumer()
        sync(Producer(listOf(shared)), consumer)
        val otherPhone = Producer(listOf(FakeMedia("other-key", shared.bytes)))
        consumer.registry.register(PairedPeer(deviceId(3), "Other", DeviceRole.PRODUCER))

        val (sent, _) = sync(otherPhone, consumer, from = deviceId(3))

        sent.getOrThrow() shouldBe SyncResult(alreadyOnConsumer = 1)
        consumer.sink.stored.size shouldBe 1
    }

    @Test
    fun `an edited photo is sent again as a new version`() {
        val original = media("a", 3000)
        val producer = Producer(listOf(original))
        val consumer = Consumer()
        sync(producer, consumer)
        producer.source.put(FakeMedia("a", original.bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }))

        val (sent, _) = sync(producer, consumer)

        sent.getOrThrow().sent shouldBe 1
        consumer.sink.stored.size shouldBe 2
    }

    @Test
    fun `a dropped connection resumes where it stopped`() {
        val big = media("video", 5 * MIB)
        val producer = Producer(listOf(big))
        val consumer = Consumer()

        val (first, _) = sync(producer, consumer, Faults(cutAfter = 3L * MIB))
        first.isFailure shouldBe true
        val kept = consumer.sink.partialSize(producerId, big.descriptor.fingerprint)
        kept shouldBeGreaterThan 0

        val (second, _) = sync(producer, consumer)

        second.getOrThrow().sent shouldBe 1
        second.getOrThrow().bytesSent shouldBeLessThan big.bytes.size.toLong()
        consumer.sink.storedContents() shouldBe setOf(big.bytes.toByteString())
        consumer.sink.partials.shouldBeEmpty()
    }

    @Test
    fun `bytes corrupted in transit are rejected and sent again next time`() {
        val photo = media("a", 2 * MIB)
        val producer = Producer(listOf(photo))
        val consumer = Consumer()

        val (first, _) = sync(producer, consumer, Faults(flipByteAt = MIB.toLong()))

        first.getOrThrow() shouldBe SyncResult(failed = 1, bytesSent = photo.bytes.size.toLong())
        consumer.sink.stored.size shouldBe 0
        consumer.ledger.items.shouldBeEmpty()

        sync(producer, consumer).first.getOrThrow().sent shouldBe 1
        consumer.sink.storedContents() shouldBe setOf(photo.bytes.toByteString())
    }

    @Test
    fun `items that don't fit are deferred, not failed`() {
        val producer = Producer(listOf(media("small", 1000), media("large", 50_000)))
        val consumer = Consumer(FakeMediaSink(availableBytes = 10_000))

        val (sent, served) = sync(producer, consumer)

        sent.getOrThrow() shouldBe SyncResult(sent = 1, deferred = 1, bytesSent = 1000)
        served.getOrThrow() shouldBe ServeResult.Synced(1, 0, 0, bytesReceived = 1000, waitingForSpace = 1)
        producer.ledger.entries.size shouldBe 1
    }

    @Test
    fun `a storage failure is reported and nothing is recorded`() {
        val producer = Producer(listOf(media("a", 2 * MIB)))
        val consumer = Consumer(FakeMediaSink(failWritesAfter = MIB.toLong()))

        val (sent, served) = sync(producer, consumer)

        sent.getOrThrow().failed shouldBe 1
        served.getOrThrow() shouldBe
            ServeResult.Synced(received = 0, alreadyHad = 0, failed = 1, bytesReceived = 2L * MIB)
        consumer.ledger.items.shouldBeEmpty()
        producer.ledger.entries.size shouldBe 0
    }

    @Test
    fun `a photo deleted after indexing is skipped`() {
        val producer = Producer(listOf(media("gone", 1000), media("kept", 1000)))
        val consumer = Consumer()
        val deleting = object : MediaSource by producer.source {
            override fun open(item: SourceItem) =
                if (item.key == "gone") throw java.io.FileNotFoundException("deleted") else producer.source.open(item)
        }
        val connection = ConnectionPair(producerId, consumerId)

        val (sent, _) = runBothSides(
            producer = { ProducerSession(local, deleting, producer.ledger).sync(connection.producerEnd) },
            consumer = {
                ConsumerSession(local, consumer.registry, consumer.ledger, consumer.sink, FakePairingGate(TOKEN))
                    .serve(connection.consumerEnd)
            },
        )

        sent.getOrThrow().sent shouldBe 1
        sent.getOrThrow().failed shouldBe 1
    }

    @Test
    fun `an unpaired producer can't send anything`() {
        val consumer = Consumer()
        val stranger = deviceId(99)

        val (sent, served) = sync(Producer(listOf(media("a", 100))), consumer, from = stranger)

        sent.exceptionOrNull().shouldBeInstanceOf<PeerEndedSessionException>().byeReason shouldBe Bye.Reason.NOT_PAIRED
        served.getOrThrow() shouldBe ServeResult.Rejected(Bye.Reason.NOT_PAIRED)
        consumer.sink.stored.size shouldBe 0
    }

    @Test
    fun `cancelling stops after the current file and says why`() {
        val producer = Producer(List(5) { media("item$it", 1000) })
        val consumer = Consumer()
        var allowed = 2
        val connection = ConnectionPair(producerId, consumerId)

        val (sent, served) = runBothSides(
            producer = {
                ProducerSession(local, producer.source, producer.ledger, shouldContinue = { allowed-- > 0 })
                    .sync(connection.producerEnd)
            },
            consumer = {
                ConsumerSession(local, consumer.registry, consumer.ledger, consumer.sink, FakePairingGate(TOKEN))
                    .serve(connection.consumerEnd)
            },
        )

        sent.getOrThrow().cancelled shouldBe true
        sent.getOrThrow().sent shouldBe 1
        served.getOrThrow().shouldBeInstanceOf<ServeResult.Synced>().received shouldBe 1
    }

    @Test
    fun `progress reaches the totals`() {
        val media = listOf(media("a", 2 * MIB), media("b", 500))
        val producer = Producer(media)
        val updates = mutableListOf<TransferProgress>()
        val connection = ConnectionPair(producerId, consumerId)
        val consumer = Consumer()

        runBothSides(
            producer = {
                ProducerSession(local, producer.source, producer.ledger, progress = { updates += it })
                    .sync(connection.producerEnd)
            },
            consumer = {
                ConsumerSession(local, consumer.registry, consumer.ledger, consumer.sink, FakePairingGate(TOKEN))
                    .serve(connection.consumerEnd)
            },
        )

        updates.last() shouldBe TransferProgress(2, 2, 2L * MIB + 500, 2L * MIB + 500, updates.last().currentItem)
    }

    /** Syncs, and returns the last progress each side reported: the one with the most bytes done. */
    private fun syncWithProgress(producer: Producer, consumer: Consumer): Pair<TransferProgress, TransferProgress?> {
        val sent = CopyOnWriteArrayList<TransferProgress>()
        val received = CopyOnWriteArrayList<TransferProgress>()
        val connection = ConnectionPair(producerId, consumerId)
        runBothSides(
            producer = {
                ProducerSession(local, producer.source, producer.ledger, progress = { sent += it })
                    .sync(connection.producerEnd)
            },
            consumer = {
                ConsumerSession(
                    local,
                    consumer.registry,
                    consumer.ledger,
                    consumer.sink,
                    FakePairingGate(TOKEN),
                    progress = { received += it },
                ).serve(connection.consumerEnd)
            },
        )
        return sent.maxBy { it.bytesDone } to received.maxByOrNull { it.bytesDone }
    }

    @Test
    fun `the part of a resumed file the consumer kept is done, but didn't move`() {
        val big = media("video", 5 * MIB)
        val producer = Producer(listOf(big))
        val consumer = Consumer()
        sync(producer, consumer, Faults(cutAfter = 3L * MIB))
        val kept = consumer.sink.partialSize(producerId, big.descriptor.fingerprint)

        val (sent, received) = syncWithProgress(producer, consumer)

        sent.bytesDone shouldBe 5L * MIB
        sent.bytesSkipped shouldBe kept
        received!!.bytesSkipped shouldBe kept
        received.bytesMoved shouldBe 5L * MIB - kept
    }

    @Test
    fun `files that aren't sent after all are done, but didn't move`() {
        val had = listOf(media("a", 1000), media("b", 2000))
        val consumer = Consumer(FakeMediaSink(availableBytes = 10_000))
        sync(Producer(had), consumer)
        // Reinstalled: the producer offers again what the consumer has, and one more that doesn't fit.
        val reinstalled = Producer(had + media("c", 500) + media("large", 50_000))

        val (sent, _) = syncWithProgress(reinstalled, consumer)

        sent.bytesDone shouldBe sent.bytesTotal // Nothing is left: the time left can reach 0.
        sent.bytesMoved shouldBe 500
    }

    @Test
    fun `the last progress comes before the session ends, never after`() {
        val producer = Producer(List(3) { media("item$it", 500) })
        val consumer = Consumer()
        val connection = ConnectionPair(producerId, consumerId)
        val itemsReported = AtomicInteger()

        val (reportedWhenDone, _) = runBothSides(
            producer = {
                val session = ProducerSession(local, producer.source, producer.ledger, progress = { update ->
                    Thread.sleep(SLOW_LISTENER_MILLIS) // A slow screen update, so one coming late would show.
                    itemsReported.set(update.itemsDone)
                })
                session.sync(connection.producerEnd)
                itemsReported.get()
            },
            consumer = {
                ConsumerSession(local, consumer.registry, consumer.ledger, consumer.sink, FakePairingGate(TOKEN))
                    .serve(connection.consumerEnd)
            },
        )

        reportedWhenDone.getOrThrow() shouldBe 3
    }

    private fun media(key: String, size: Int) = FakeMedia(key, Random(key.hashCode()).nextBytes(size))

    private companion object {
        const val MIB = 1024 * 1024
        const val SLOW_LISTENER_MILLIS = 50L
        val TOKEN = "one-time-token!!".encodeToByteArray().toByteString()
    }
}
