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
import io.github.danyk20.mediasync.sync.engine.testing.FakeTestFileQueue
import io.github.danyk20.mediasync.sync.engine.testing.FakeTestFileStore
import io.github.danyk20.mediasync.sync.engine.testing.Faults
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

class TestFileTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")
    private val photos = listOf(FakeMedia("a", Random(1).nextBytes(3000)), FakeMedia("b", Random(2).nextBytes(500)))
    private val source = FakeMediaSource(photos)
    private val sentLedger = FakeSentLedger()
    private val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER))
    private val receivedLedger = FakeReceivedLedger()
    private val sink = FakeMediaSink()
    private val store = FakeTestFileStore()
    private val file = TestFile(size = 2 * MIB + 123, seed = 42)
    private val queue = FakeTestFileQueue(file)
    private val progress = CopyOnWriteArrayList<TransferProgress>()

    private fun producer(path: TestFilePath = TestFilePath.WIFI, shouldContinue: () -> Boolean = { true }) =
        ProducerSession(
            local,
            source,
            sentLedger,
            progress = { progress += it },
            shouldContinue = shouldContinue,
            testFiles = TestFileRoute(path, queue),
        )

    private fun consumer(testFiles: TestFileStore? = store) =
        ConsumerSession(local, registry, receivedLedger, sink, FakePairingGate(TOKEN), testFiles = testFiles)

    private fun sync(
        faults: Faults = Faults(),
        producer: ProducerSession = producer(),
        consumer: ConsumerSession = consumer(),
    ): Pair<Result<SyncResult>, Result<ServeResult>> {
        val connection = ConnectionPair(producerId, consumerId, faults)
        return runBothSides({ producer.sync(connection.producerEnd) }, { consumer.serve(connection.consumerEnd) })
    }

    @Test
    fun `it goes first, arrives exactly as sent, and is deleted without becoming a photo`() {
        val (sent, served) = sync(producer = producer(TestFilePath.TURBO))

        val photoBytes = photos.sumOf { it.bytes.size.toLong() }
        sent.getOrThrow() shouldBe SyncResult(sent = 2, bytesSent = photoBytes + file.size)
        served.getOrThrow() shouldBe ServeResult.Synced(2, 0, 0, bytesReceived = photoBytes) // Not a photo.
        val outcome = queue.checked.single()
        outcome.status shouldBe TestFileStatus.MATCHED
        outcome.size shouldBe file.size
        outcome.bytes shouldBe file.size
        outcome.path shouldBe TestFilePath.TURBO
        outcome.megabytesPerSecond shouldBeGreaterThan 0.0
        queue.file.shouldBeNull() // No longer waiting.
        store.checked.single() shouldBe (producerId to outcome)
        store.files.single().size shouldBe file.size
        store.leftovers() shouldBe 0
        // Only the photos reach the gallery and the ledgers.
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        receivedLedger.items.map { it.descriptor.displayName }.sorted() shouldBe listOf("a.jpg", "b.jpg")
        sentLedger.sentFingerprints(consumerId) shouldBe photos.map { it.descriptor.fingerprint }.toSet()
        // First: every report of it comes before any photo's.
        progress.first().testFile shouldBe true
        progress.dropWhile { it.testFile }.none { it.testFile } shouldBe true
        progress.last().bytesTotal shouldBe photoBytes + file.size
        progress.last().itemsTotal shouldBe 2
    }

    @Test
    fun `a test file that changed on the way is reported, and deleted all the same`() {
        val (sent, _) = sync(Faults(flipByteAt = 5_000))

        sent.getOrThrow().sent shouldBe 2
        queue.checked.single().status shouldBe TestFileStatus.MISMATCHED
        queue.file.shouldBeNull() // Reported: the user adds a new one to test again.
        store.checked.single().second.status shouldBe TestFileStatus.MISMATCHED
        store.leftovers() shouldBe 0
        sink.stored.size shouldBe 2
    }

    @Test
    fun `a test file that couldn't be written there is reported as such`() {
        store.failWritesAfter = MIB

        sync().first.getOrThrow()

        queue.checked.single().status shouldBe TestFileStatus.NOT_STORED
        store.leftovers() shouldBe 0
    }

    @Test
    fun `an older receiving phone never gets one, and it keeps waiting`() {
        val (sent, served) = sync(consumer = consumer(testFiles = null))

        sent.getOrThrow().sent shouldBe 2
        served.getOrThrow() shouldBe ServeResult.Synced(2, 0, 0, photos.sumOf { it.bytes.size.toLong() })
        queue.held shouldContainExactly listOf(TestFileHeld.RECEIVER_OUTDATED)
        queue.file shouldBe file
        queue.checked.shouldBeEmpty()
        progress.none { it.testFile } shouldBe true
    }

    @Test
    fun `without room for it, it keeps waiting and the photos still go`() {
        sink.availableBytes = MIB

        sync().first.getOrThrow().sent shouldBe 2

        queue.held shouldContainExactly listOf(TestFileHeld.NO_SPACE)
        queue.file shouldBe file
        store.files.shouldBeEmpty()
    }

    @Test
    fun `when it can't be written at all, it keeps waiting`() {
        store.failOpen = true

        sync().first.getOrThrow().sent shouldBe 2

        queue.held shouldContainExactly listOf(TestFileHeld.NOT_WRITABLE)
        queue.file shouldBe file
    }

    @Test
    fun `a dropped sync keeps it waiting, and the receiving phone deletes what arrived`() {
        val (sent, served) = sync(Faults(cutAfter = MIB))

        sent.isFailure shouldBe true
        served.isFailure shouldBe true
        queue.file shouldBe file
        queue.checked.shouldBeEmpty()
        store.checked.shouldBeEmpty()
        store.files.size shouldBe 1
        store.leftovers() shouldBe 0
        sink.stored.size shouldBe 0
    }

    @Test
    fun `a sync stopped while it's sent keeps it waiting, and sends nothing else`() {
        val big = TestFile(size = 12 * MIB, seed = 1)
        queue.file = big
        val asked = AtomicInteger()
        // Yes before it starts and at its first chunk; no at the next question, 8 MiB into it.
        val (sent, _) = sync(producer = producer(shouldContinue = { asked.incrementAndGet() <= 2 }))

        sent.getOrThrow().cancelled shouldBe true
        queue.held shouldContainExactly listOf(TestFileHeld.STOPPED)
        queue.file shouldBe big
        store.checked.shouldBeEmpty()
        store.leftovers() shouldBe 0
        sink.stored.size shouldBe 0
    }

    @Test
    fun `a sync stopped before it begins doesn't start it`() {
        sync(producer = producer(shouldContinue = { false })).first.getOrThrow()

        queue.held shouldContainExactly listOf(TestFileHeld.STOPPED)
        store.files.shouldBeEmpty()
    }

    @Test
    fun `the relay saver holds back videos, never the test file`() {
        val connection = ConnectionPair(producerId, consumerId)
        val (sent, _) = runBothSides(
            producer = {
                ParallelSync(
                    session = { listener ->
                        val route = TestFileRoute(TestFilePath.RELAY, queue)
                        ProducerSession(local, source, sentLedger, listener, testFiles = route)
                    },
                    connect = { null },
                    holdBack = { true }, // Everything waits for Wi-Fi.
                ).sync(connection.producerEnd)
            },
            consumer = { consumer().serve(connection.consumerEnd) },
        )

        sent.getOrThrow().heldBack shouldBe 2
        queue.checked.single().path shouldBe TestFilePath.RELAY
        queue.checked.single().status shouldBe TestFileStatus.MATCHED
    }

    @Test
    fun `parallel connections take it once, and none joins while it's sent`() {
        val queue = WorkQueue(emptyList(), file)

        queue.totalBytes shouldBe file.size
        queue.sendingTestFile shouldBe true
        queue.takeTestFile() shouldBe file
        queue.takeTestFile().shouldBeNull()
        queue.sendingTestFile shouldBe true
        queue.testFileDone()
        queue.sendingTestFile shouldBe false
        WorkQueue(emptyList()).sendingTestFile shouldBe false
    }

    @Test
    fun `its bytes come from its seed, and look random`() {
        fun bytes(seed: Long, size: Int, chunk: Int): ByteArray {
            val generator = TestFileBytes(seed)
            val out = ByteArray(size)
            val buffer = ByteArray(chunk)
            var at = 0
            while (at < size) {
                val n = minOf(chunk, size - at)
                generator.fill(buffer, n)
                buffer.copyInto(out, at, 0, n)
                at += n
            }
            return out
        }

        bytes(7, 4096, 1024).toList() shouldBe bytes(7, 4096, 1024).toList()
        bytes(7, 4096, 1024).toList() shouldNotBe bytes(8, 4096, 1024).toList()
        bytes(7, 4096, 1024).distinct().size shouldBe 256
    }

    private companion object {
        const val MIB = 1024L * 1024
        val TOKEN = "token".encodeToByteArray().toByteString()
    }
}
