/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.transport.lan.testIdentity
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.Closeable
import java.net.InetAddress
import java.time.LocalTime
import javax.net.SocketFactory
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Two phones' runtimes, each with its own database, pairing and syncing over loopback TLS. */
@RunWith(RobolectricTestRunner::class)
class RuntimeEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loopback = AddressProvider { listOf(InetAddress.getLoopbackAddress()) }
    private val noDiscovery = object : ServiceDiscovery {
        override fun advertise(port: Int, serviceId: String) = Closeable {}

        override suspend fun find(serviceId: String, timeoutMillis: Long): String? = null
    }

    // The receiving phone.
    private val consumerDb = database()
    private val consumerPeers = PeerStore(consumerDb.peerDao())
    private val received = RoomReceivedLedger(consumerDb.receivedItemDao())
    private val sink = FakeMediaSink()
    private val consumerIdentity = testIdentity("Pixel 2 XL")
    private var consumerSchedule: SyncSchedule = SyncSchedule.Default
    private val receiver = ConsumerReceiver(
        identity = { consumerIdentity },
        peers = consumerPeers,
        ledger = received,
        sink = sink,
        device = { LocalDevice("Pixel 2 XL", "test", consumerSchedule) },
        addresses = loopback,
        discovery = noDiscovery,
        scope = scope,
        preferredPort = 0,
    )

    // The sending phone.
    private val producerDb = database()
    private val producerPeers = PeerStore(producerDb.peerDao())
    private val photos = List(4) { FakeMedia("photo$it", Random(it).nextBytes(300_000 + it)) }
    private val source = FakeMediaSource(photos)
    private val producerIdentity = testIdentity("Anna's Pixel")
    private val history = SyncHistory(ApplicationProvider.getApplicationContext())
    private var photoDetails = PhotoDetails.KEEP_ALL
    private val producer = ProducerSync(
        identity = { producerIdentity },
        peers = producerPeers,
        ledger = RoomSentLedger(producerDb.sentItemDao()),
        source = source,
        device = object : DeviceInfo {
            override fun local() = LocalDevice("Anna's Pixel", "test")

            override fun photoDetails() = photoDetails
        },
        discovery = noDiscovery,
        sockets = { SocketFactory.getDefault() },
        history = history,
        scope = scope,
    )

    @After
    fun tearDown() {
        receiver.stop()
        scope.cancel()
        consumerDb.close()
        producerDb.close()
    }

    @Test
    fun theSendingPhoneFollowsTheReceivingPhonesSchedule() = runBlocking<Unit> {
        receiver.start()
        consumerSchedule = SyncSchedule.EveryHours(6, LocalTime.of(3, 0))

        pairWithApproval()
        producerPeers.consumer()?.schedule shouldBe SyncSchedule.EveryHours(6, LocalTime.of(3, 0))

        // Changed on the receiving phone: the next sync, as background work runs it, brings it along.
        consumerSchedule = SyncSchedule.WhileCharging
        withTimeout(TIMEOUT) { producer.syncAndWait() }.shouldBeInstanceOf<ProducerState.Idle>()
        producerPeers.consumer()?.schedule shouldBe SyncSchedule.WhileCharging
    }

    @Test
    fun pairByLinkThenSyncNow() = runBlocking<Unit> {
        receiver.start()

        val paired = pairWithApproval()

        paired shouldBe PairingResult.Paired("Pixel 2 XL")
        consumerPeers.isPaired(producerIdentity.id) shouldBe true
        receiver.state.value.lastPaired?.name shouldBe "Anna's Pixel"
        producer.pendingCount() shouldBe Pending(toSend = 4)

        producer.syncNow()
        val done = withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(
                sent = 4,
                bytesSent = photos.sumOf { it.bytes.size.toLong() },
                consumerSchedule = SyncSchedule.Default,
            )
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        producer.pendingCount() shouldBe Pending()
        history.record.value.lastSyncMillis.shouldNotBeNull()

        // Nothing new: the next sync transfers nothing.
        producer.syncNow()
        val again = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult != done.lastResult }
        }
        again.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(consumerSchedule = SyncSchedule.Default)
    }

    @Test
    fun pairingAgainKeepsWhatWasSentAndSyncsOn() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval() shouldBe PairingResult.Paired("Pixel 2 XL")
        producer.syncNow()
        val first = withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        // The QR code is scanned again on the phone that is already paired.
        pairWithApproval() shouldBe PairingResult.Paired("Pixel 2 XL")

        withTimeout(TIMEOUT) { receiver.state.first { it.lastPaired != null } }.lastPaired?.name shouldBe "Anna's Pixel"
        consumerPeers.isPaired(producerIdentity.id) shouldBe true
        received.observeCountFrom(producerIdentity.id).first() shouldBe photos.size
        producer.pendingCount() shouldBe Pending()

        val newPhoto = FakeMedia("new", Random(photos.size).nextBytes(200_000))
        source.put(newPhoto)
        producer.syncNow()
        val again = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it != first }
        }

        again.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(sent = 1, bytesSent = newPhoto.bytes.size.toLong(), consumerSchedule = SyncSchedule.Default)
        sink.storedContents() shouldBe (photos + newPhoto).map { it.bytes.toByteString() }.toSet()
    }

    @Test
    fun aFullReceivingPhoneIsShownOnBothPhones() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        sink.availableBytes = photos.first().bytes.size.toLong() // Room for one photo only.

        producer.syncNow()
        val done = withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        (done as ProducerState.Idle).lastResult?.sent shouldBe 1
        history.record.value.waitingForSpace shouldBe photos.size - 1
        withTimeout(TIMEOUT) { receiver.state.first { it.waitingForSpace > 0 } }.waitingForSpace shouldBe
            photos.size - 1
    }

    @Test
    fun theChosenPhotoDetailsApplyToWhatIsSentAndCounted() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }
        val golden = { name: String -> javaClass.getResourceAsStream("/golden/$name")!!.use { it.readBytes() } }
        source.put(FakeMedia("located", golden("pixel.jpg")))
        source.put(FakeMedia("raw", golden("raw.dng"), displayName = "raw.dng", mimeType = "image/x-adobe-dng"))
        photoDetails = PhotoDetails.REMOVE_ALL

        producer.pendingCount() shouldBe Pending(toSend = 1, withheld = 1)
        producer.syncNow()
        val done = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult?.withheld == 1 }
        }

        (done as ProducerState.Idle).lastResult?.sent shouldBe 1
        val stored = sink.stored.values.map { String(it, Charsets.ISO_8859_1) }
        stored.any { "Zurich" in it || "Pixel 8" in it } shouldBe false
        stored.size shouldBe photos.size + 1
    }

    @Test
    fun theDiagnosticLogSaysWhatHappenedWithoutNamingFilesOrPhones() = runBlocking<Unit> {
        val entries = java.util.Collections.synchronizedList(mutableListOf<String>())
        Diagnostics.install { area, message -> entries += "${area.label} $message" }
        try {
            receiver.start()
            pairWithApproval()
            producer.syncNow()
            withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }
            withTimeout(TIMEOUT) {
                while (entries.none { it.startsWith("receive Session from") && "received" in it }) delay(POLL)
            }

            entries.single { it.startsWith("send Sync done") } shouldContain "sent 4"
            entries.single { it.startsWith("receive Session from") && "received" in it } shouldContain "received 4"
            entries.any { it.startsWith("receive Listening on port") } shouldBe true
            val everything = synchronized(entries) { entries.joinToString("\n") }
            listOf("photo0", ".jpg", "Pixel 2 XL", "Anna's Pixel").filter { it in everything } shouldBe emptyList()
        } finally {
            Diagnostics.install { _, _ -> }
        }
    }

    @Test
    fun aFailedSyncIsLoggedWithWhatWentWrong() = runBlocking<Unit> {
        val entries = java.util.Collections.synchronizedList(mutableListOf<String>())
        Diagnostics.install { area, message -> entries += "${area.label} $message" }
        try {
            receiver.start()
            pairWithApproval()
            receiver.stop() // The receiving phone is gone.

            producer.syncNow()
            withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Failed } }

            entries.any { it.startsWith("send Couldn't connect to 127.0.0.x:") } shouldBe true
            entries.any { it.startsWith("send Not found on the network") } shouldBe true
        } finally {
            Diagnostics.install { _, _ -> }
        }
    }

    @Test
    fun anUnpairedPhoneCantSync() = runBlocking<Unit> {
        receiver.start()
        val link = receiver.openPairing().shouldNotBeNull()
        receiver.closePairing()

        // Remember the consumer without the consumer knowing us, as if it had been unpaired there.
        PeerStore(producerDb.peerDao()).register(
            io.github.danyk20.mediasync.sync.engine.PairedPeer(
                link.consumer,
                link.consumerName,
                io.github.danyk20.mediasync.core.model.DeviceRole.CONSUMER,
            ),
            link.addresses.first(),
        )
        producer.syncNow()

        val failed = withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Failed } }
        // The consumer tells it explicitly, so the user knows to pair again.
        (failed as ProducerState.Failed).reason shouldBe SyncFailure.NOT_ALLOWED
        sink.stored.size shouldBe 0
    }

    /** Shows the consumer's QR code, scans it on the producer, and approves the request on the consumer. */
    private suspend fun pairWithApproval(): PairingResult {
        val link = receiver.openPairing().shouldNotBeNull()
        scope.launch { receiver.pendingApproval.first { it != null }?.accept() }
        return producer.pair(link)
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MediaSyncDatabase::class.java,
    ).allowMainThreadQueries().build()

    private companion object {
        const val TIMEOUT = 20_000L
        const val POLL = 20L
    }
}
