/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.metadata.ByteReader
import io.github.danyk20.mediasync.core.metadata.PhotoDetailsRemover
import io.github.danyk20.mediasync.core.metadata.Redaction
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

/** The *Photo details* setting in a session (docs/PLAN.md §6.3): what's removed never leaves the producer. */
class PhotoDetailsSessionTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")

    private val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER))
    private val received = FakeReceivedLedger()
    private val sink = FakeMediaSink()
    private val sent = FakeSentLedger()

    private fun sync(source: MediaSource, details: PhotoDetails, faults: Faults = Faults()): Result<SyncResult> {
        val connection = ConnectionPair(producerId, consumerId, faults)
        return runBothSides(
            producer = {
                ProducerSession(local, source, sent, details = details).sync(connection.producerEnd)
            },
            consumer = {
                ConsumerSession(local, registry, received, sink, FakePairingGate(TOKEN)).serve(connection.consumerEnd)
            },
        ).first
    }

    @Test
    fun removedDetailsNeverLeaveThePhone() {
        val photo = FakeMedia("photo", golden("pixel.jpg"))

        sync(FakeMediaSource(listOf(photo)), PhotoDetails.REMOVE_LOCATION).getOrThrow().sent shouldBe 1

        val stored = sink.stored.values.single()
        stored.toList() shouldBe redacted(photo.bytes, PhotoDetails.REMOVE_LOCATION).toList()
        String(stored, Charsets.ISO_8859_1).contains("Zurich") shouldBe false
        received.items.single().descriptor.size shouldBe stored.size.toLong()
        // Recorded under the original's fingerprint: it isn't offered again.
        sent.sentFingerprints(consumerId) shouldBe setOf(photo.descriptor.fingerprint)
        sync(FakeMediaSource(listOf(photo)), PhotoDetails.REMOVE_LOCATION).getOrThrow() shouldBe
            SyncResult(consumerSchedule = null)
    }

    @Test
    fun removeAllAlsoDropsTheAlbumAndFavoriteButKeepsTheDate() {
        val meta =
            MediaMeta(capturedAtMillis = 1_791_970_200_000, utcOffsetMinutes = 120, album = "Holidays", favorite = true)
        val photo = FakeMedia("photo", golden("pixel.jpg"), meta = meta)

        sync(FakeMediaSource(listOf(photo)), PhotoDetails.REMOVE_ALL).getOrThrow().sent shouldBe 1

        received.items.single().descriptor.meta shouldBe meta.copy(album = null, favorite = false)
        sink.stored.values.single().toList() shouldBe redacted(photo.bytes, PhotoDetails.REMOVE_ALL).toList()
    }

    @Test
    fun aDroppedTransferResumesOnTheSameEditedBytes() {
        val video = FakeMedia("video", withPadding(golden("video.mp4"), 3 * MIB), mimeType = "video/mp4")
        val source = FakeMediaSource(listOf(video))

        sync(source, PhotoDetails.REMOVE_LOCATION, Faults(cutAfter = 2L * MIB)).isFailure shouldBe true
        sink.partialSize(producerId, video.descriptor.fingerprint) shouldBeGreaterThan 0

        val second = sync(source, PhotoDetails.REMOVE_LOCATION).getOrThrow()

        second.sent shouldBe 1
        second.bytesSent shouldBeLessThan video.bytes.size.toLong()
        sink.storedContents() shouldBe setOf(redacted(video.bytes, PhotoDetails.REMOVE_LOCATION).toByteString())
    }

    @Test
    fun rawPhotosAreWithheldUnderRemoveAllOnly() {
        val raw = FakeMedia("raw", golden("raw.dng"), mimeType = "image/x-adobe-dng")
        val source = FakeMediaSource(listOf(raw))

        sync(source, PhotoDetails.REMOVE_ALL).getOrThrow() shouldBe SyncResult(withheld = 1)
        sink.stored.values.shouldBeEmpty()
        sent.sentFingerprints(consumerId).shouldBeEmpty()

        sync(source, PhotoDetails.REMOVE_LOCATION).getOrThrow().sent shouldBe 1
    }

    @Test
    fun aPhotoChangedAfterItWasOfferedIsNotSentWithStaleEdits() {
        val photo = FakeMedia("photo", golden("pixel.jpg"))
        val edited = golden("ultrahdr.jpg") // Its details changed (and moved) since it was offered.
        val source = object : MediaSource by FakeMediaSource(listOf(photo)) {
            private var reads = 0

            override fun openRanges(item: SourceItem): RangeReader {
                val bytes = if (reads++ == 0) photo.bytes else edited
                return object : RangeReader {
                    override fun read(offset: Long, byteCount: Int) =
                        bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)

                    override fun close() = Unit
                }
            }
        }

        sync(source, PhotoDetails.REMOVE_LOCATION).getOrThrow().failed shouldBe 1

        sink.stored.values.shouldBeEmpty()
    }

    private fun redacted(file: ByteArray, details: PhotoDetails): ByteArray {
        val plan = PhotoDetailsRemover.plan(
            file.size.toLong(),
            ByteReader { o, n ->
                file.copyOfRange(
                    o.toInt(),
                    o.toInt() + n,
                )
            },
            details,
        )
        return (plan as Redaction.Edited).open(file.inputStream()).use { it.readBytes() }
    }

    /** [video] with a `free` box of random bytes after it, to make it big enough to be cut mid-way. */
    private fun withPadding(video: ByteArray, size: Int): ByteArray =
        video + ByteBuffer.allocate(8).putInt(size + 8).put("free".toByteArray()).array() + Random(1).nextBytes(size)

    private companion object {
        const val MIB = 1024 * 1024
        val TOKEN = "one-time-token!!".encodeToByteArray().toByteString()

        fun golden(name: String): ByteArray =
            PhotoDetailsSessionTest::class.java.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }
    }
}
