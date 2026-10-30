/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.LeftOutStore
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TURBO_KEY_BYTES
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.testIdentity
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.Closeable
import java.net.InetAddress
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
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
import okio.ByteString
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
    private val consumerKey = ByteArray(TURBO_KEY_BYTES) { 9 }.toByteString()
    private val consumerLinks = FakeDirectLinks()

    /** The receiving phone's rendezvous Worker, once set up (docs/PLAN.md §8.4). */
    private var consumerWorker: String? = null
    private val internet = FakeInternet { producerIdentity.id }
    private val receiver = ConsumerReceiver(
        identity = { consumerIdentity },
        peers = consumerPeers,
        ledger = received,
        sink = sink,
        device = { LocalDevice("Pixel 2 XL", "test", consumerSchedule, consumerKey, consumerWorker) },
        network = Connectivity(loopback, { SocketFactory.getDefault() }, noDiscovery, consumerLinks, internet),
        scope = scope,
        preferredPort = 0,
        directLinkIdleMillis = 300,
    )

    // The sending phone.
    private val producerDb = database()
    private val producerPeers = PeerStore(producerDb.peerDao())
    private val photos = List(4) { FakeMedia("photo$it", Random(it).nextBytes(300_000 + it)) }
    private val source = FakeMediaSource(photos)
    private val leftOut = LeftOutItems(LeftOutStore(producerDb.leftOutItemDao()), source)
    private val producerLinks = FakeDirectLinks()

    /** The sending phone is away from the receiving phone's Wi-Fi; a direct link still reaches it. */
    @Volatile
    private var offWifi = false
    private val producerIdentity = testIdentity("Anna's Pixel")
    private val history = SyncHistory(ApplicationProvider.getApplicationContext())
    private var photoDetails = PhotoDetails.KEEP_ALL
    private var videosOverRelay = false
    private var sendingOrder = SendingOrder.Default
    private val producer = ProducerSync(
        identity = { producerIdentity },
        peers = producerPeers,
        ledger = RoomSentLedger(producerDb.sentItemDao()),
        source = source,
        leftOut = leftOut,
        device = object : DeviceInfo {
            override fun local() = LocalDevice("Anna's Pixel", "test")

            override fun photoDetails() = photoDetails

            override fun videosOverRelay() = videosOverRelay

            override fun sendingOrder() = sendingOrder
        },
        network = Connectivity(loopback, {
            if (offWifi) NoNetwork else SocketFactory.getDefault()
        }, noDiscovery, producerLinks, internet),
        history = history,
        conditions = { null },
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
    fun awayFromTheReceivingPhonesWifiItSyncsThroughTheInternet() = runBlocking<Unit> {
        consumerWorker = "https://mediasync.example.workers.dev"
        receiver.start()
        pairWithApproval()
        // A sync over Wi-Fi tells the sending phone the receiving phone's Worker and gives the pair its secret.
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }
        withTimeout(TIMEOUT) { receiver.state.first { it.overInternet } }
        val address = producerPeers.consumer()?.lastAddress

        source.put(FakeMedia("away", Random(9).nextBytes(200_000)))
        offWifi = true
        producer.syncNow()
        val done = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult?.sent == 1 }
        }

        (done as ProducerState.Idle).lastResult?.alreadyOnConsumer shouldBe 0
        sink.stored.size shouldBe photos.size + 1
        internet.connects.get() shouldBe 1
        producerPeers.consumer()?.lastAddress shouldBe address // Not an address on the local network.
    }

    @Test
    fun theSendingOrderIsFollowedAndOverTheInternetThePhoneSendsPhotosFirst() = runBlocking<Unit> {
        consumerWorker = "https://mediasync.example.workers.dev"
        videosOverRelay = true
        receiver.start()
        pairWithApproval()
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }
        withTimeout(TIMEOUT) { receiver.state.first { it.overInternet } }
        val video = FakeMedia("video", Random(1).nextBytes(1_000), "video.mp4", "video/mp4", MediaMeta(3_000))
        val photo = FakeMedia("photo", Random(2).nextBytes(1_000), meta = MediaMeta(1_000))
        suspend fun sendAway(order: SendingOrder): List<String> {
            sendingOrder = order
            val before = synchronized(source.opened) { source.opened.size }
            source.put(video.copyAs("${video.key}-$order"))
            source.put(photo.copyAs("${photo.key}-$order"))
            producer.syncNow()
            withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult?.sent == 2 } }
            return synchronized(source.opened) { source.opened.drop(before).map { it.substringBefore('-') } }
        }
        offWifi = true // Over the internet: one connection, so the order is exact.

        sendAway(SendingOrder.AUTOMATIC) shouldBe listOf("photo", "video")
        sendAway(SendingOrder.NEWEST_FIRST) shouldBe listOf("video", "photo")
        sendAway(SendingOrder.OLDEST_FIRST) shouldBe listOf("photo", "video")
    }

    @Test
    fun theReceivingPhoneReadsItsRelayMeterAsAPair() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        receiver.relayUsage() shouldBe null // No Worker.

        consumerWorker = "https://mediasync.example.workers.dev"
        receiver.relayUsage() shouldBe null // No sending phone with a pair secret yet.
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        receiver.relayUsage()?.usedBytes shouldBe 312_000_000_000
        internet.meterAsked shouldBe listOf("https://mediasync.example.workers.dev" to producerIdentity.id)
    }

    @Test
    fun throughTheRelayVideosWaitUnlessTheUserLetsThemGo() = runBlocking<Unit> {
        consumerWorker = "https://mediasync.example.workers.dev"
        receiver.start()
        pairWithApproval()
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }
        withTimeout(TIMEOUT) { receiver.state.first { it.overInternet } }
        source.put(FakeMedia("photo", Random(11).nextBytes(100_000)))
        source.put(FakeMedia("video", Random(12).nextBytes(300_000), "video.mp4", "video/mp4"))
        offWifi = true
        internet.throughRelay = true

        producer.syncNow()
        val saved = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult?.heldBack == 1 }
        } as ProducerState.Idle
        saved.lastResult?.sent shouldBe 1

        videosOverRelay = true
        producer.syncNow()
        val sent = withTimeout(TIMEOUT) {
            producer.state.first {
                it is ProducerState.Idle &&
                    it.lastResult?.let { result -> result.heldBack == 0 && result.sent == 1 } == true
            }
        } as ProducerState.Idle
        sent.lastResult?.sent shouldBe 1
        sink.stored.size shouldBe photos.size + 2
    }

    @Test
    fun onMobileDataASyncStopsAtTheMonthsLimitAndCountsWhatItSent() = runBlocking<Unit> {
        consumerWorker = "https://mediasync.example.workers.dev"
        receiver.start()
        pairWithApproval()
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }
        withTimeout(TIMEOUT) { receiver.state.first { it.overInternet } }
        repeat(5) { source.put(FakeMedia("away$it", Random(20 + it).nextBytes(200_000))) }
        offWifi = true
        internet.mobileDataLeft = 250_000 // About one photo's worth.

        producer.syncNow()
        val done = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult?.cancelled == true }
        } as ProducerState.Idle

        val result = done.lastResult.shouldNotBeNull()
        result.sent shouldBe 2 // The one that reached the limit is finished, then it stops.
        internet.mobileDataCounted.get() shouldBe result.bytesSent
    }

    @Test
    fun overMobileDataItDoesntSyncThroughTheInternet() = runBlocking<Unit> {
        consumerWorker = "https://mediasync.example.workers.dev"
        receiver.start()
        pairWithApproval()
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        internet.allowed = false
        offWifi = true
        producer.syncNow()

        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Failed } } shouldBe
            ProducerState.Failed(SyncFailure.UNREACHABLE)
        internet.connects.get() shouldBe 0
    }

    @Test
    fun withoutAWorkerTheReceivingPhoneDoesntListenOnTheInternet() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        receiver.state.value.overInternet shouldBe false
        consumerWorker = "https://mediasync.example.workers.dev" // Set up in settings, which refresh it.
        receiver.refreshInternet()
        withTimeout(TIMEOUT) { receiver.state.first { it.overInternet } }
        receiver.stop()
        receiver.state.value.overInternet shouldBe false
        internet.listening shouldBe emptyMap()
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
        producer.pendingCount() shouldBe Pending(toSend = 4, bytes = photos.sumOf { it.bytes.size.toLong() })

        producer.syncNow()
        val done = withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(
                sent = 4,
                bytesSent = photos.sumOf { it.bytes.size.toLong() },
                consumerSchedule = SyncSchedule.Default,
                consumerTurboKey = consumerKey,
                consumerPairSecret = consumerPeers.pairSecret(producerIdentity.id),
            )
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        producer.pendingCount() shouldBe Pending()
        history.record.value.lastSyncMillis.shouldNotBeNull()
        // Both phones now share their pair's secret for internet signaling (ADR 0009 §4).
        producerPeers.consumer()?.pairSecret shouldBe consumerPeers.pairSecret(producerIdentity.id)

        // Nothing new: the next sync transfers nothing.
        producer.syncNow()
        val again = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult != done.lastResult }
        }
        again.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(
                consumerSchedule = SyncSchedule.Default,
                consumerTurboKey = consumerKey,
                consumerPairSecret = consumerPeers.pairSecret(producerIdentity.id),
            )
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
            SyncResult(
                sent = 1,
                bytesSent = newPhoto.bytes.size.toLong(),
                consumerSchedule = SyncSchedule.Default,
                consumerTurboKey = consumerKey,
                consumerPairSecret = consumerPeers.pairSecret(producerIdentity.id),
            )
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

        // What's sent counts, as it is on the phone: the RAW photo is held back.
        producer.pendingCount() shouldBe Pending(toSend = 1, withheld = 1, bytes = golden("pixel.jpg").size.toLong())
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
    fun whatTheUserLeavesOutIsntSentUntilBroughtBack() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        val waiting = producer.waitingItems()
        val (left, rest) = waiting.partition { it.key == "photo0" }

        leftOut.leaveOut(left)

        producer.pendingCount() shouldBe Pending(toSend = 3, bytes = rest.sumOf { it.descriptor.size }, leftOut = 1)
        producer.waitingItems() shouldBe waiting // Still shown, to bring back.
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult?.sent == 3 } }
        sink.stored.size shouldBe 3
        producer.pendingCount() shouldBe Pending(leftOut = 1)

        leftOut.bringBack(left)

        producer.pendingCount() shouldBe Pending(toSend = 1, bytes = left.single().descriptor.size)
        producer.syncNow()
        withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult?.sent == 1 } }
        sink.stored.size shouldBe photos.size
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
    fun theLinkTestMeasuresOverSeveralConnectionsAndStoresNothing() = runBlocking<Unit> {
        val entries = java.util.Collections.synchronizedList(mutableListOf<String>())
        Diagnostics.install { area, message -> entries += "${area.label} $message" }
        try {
            receiver.start()
            pairWithApproval()

            val speed = producer.linkTest(streams = 2, durationMillis = 300).shouldNotBeNull()

            speed.megabytesPerSecond shouldBeGreaterThan 0.0
            sink.stored.size shouldBe 0
            entries.any { it.startsWith("send Link test over 2 connection(s)") } shouldBe true
            withTimeout(TIMEOUT) {
                while (entries.count { it.startsWith("receive Session from") && "link test" in it } < 2) delay(POLL)
            }
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

    @Test
    fun turboSendsOverTheDirectLinkTheReceivingPhoneOpenedAndClosesItAfterwards() = runBlocking<Unit> {
        val entries = java.util.Collections.synchronizedList(mutableListOf<String>())
        Diagnostics.install { area, message -> entries += "${area.label} $message" }
        try {
            receiver.start()
            pairWithApproval()
            // On the phones, a Wi-Fi Direct group; here, the receiving phone's loopback address.
            consumerLinks.answer = { port, _ ->
                TurboAnswer.Ready(DirectLink("DIRECT-ab-MediaSync", "pass", "127.0.0.1", port))
            }

            val done = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }

            done.shouldBeInstanceOf<ProducerState.Idle>().lastResult?.sent shouldBe 4
            done.turboProblem shouldBe null
            producerLinks.joined?.networkName shouldBe "DIRECT-ab-MediaSync"
            producerLinks.left shouldBe true
            entries.any { it.startsWith("send Sync done over Turbo") } shouldBe true
            // The receiving phone closes the link once nothing has come over it for a while.
            withTimeout(TIMEOUT) { receiver.state.first { !it.directLinkOpen } }
            withTimeout(TIMEOUT) { while (consumerLinks.closed.isEmpty()) delay(POLL) }
        } finally {
            Diagnostics.install { _, _ -> }
        }
    }

    @Test
    fun turboFallsBackToWifiAndSaysWhy() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()

        // The receiving phone's user hasn't allowed it: everything goes over Wi-Fi, and it asks there.
        consumerLinks.answer = { _, _ -> TurboAnswer.Unavailable(TurboUnavailable.NOT_ALLOWED) }
        val refused = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }
        refused.shouldBeInstanceOf<ProducerState.Idle>().turboProblem shouldBe TurboProblem.RECEIVER_NOT_ALLOWED
        refused.lastResult?.sent shouldBe 4
        receiver.state.value.turboNeedsPermission shouldBe true
        producerLinks.joined shouldBe null

        // The link is there, but this phone can't join it.
        source.put(FakeMedia("new", Random(9).nextBytes(100_000)))
        consumerLinks.answer = { port, _ ->
            TurboAnswer.Ready(DirectLink("DIRECT-cd-MediaSync", "pass", "127.0.0.1", port))
        }
        producerLinks.joinable = { false }
        val notJoined = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }
        notJoined.shouldBeInstanceOf<ProducerState.Idle>().turboProblem shouldBe TurboProblem.NO_LINK
        notJoined.lastResult?.sent shouldBe 1
        receiver.state.value.turboNeedsPermission shouldBe false
    }

    @Test
    fun turboGoesOverWifiAwareWhenBothPhonesHaveIt() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        consumerLinks.aware = true
        producerLinks.aware = true

        val done = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult?.sent shouldBe 4
        done.turboProblem shouldBe null
        consumerLinks.kinds shouldBe listOf(LinkKind.WIFI_AWARE)
        producerLinks.joined?.kind shouldBe LinkKind.WIFI_AWARE
        producerLinks.joined?.networkName shouldStartWith "MediaSync-"
        producerLinks.left shouldBe true
    }

    @Test
    fun aWifiAwareLinkThatCantBeJoinedMakesWayForWifiDirect() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        consumerLinks.aware = true
        consumerLinks.answer = { port, _ ->
            TurboAnswer.Ready(DirectLink("DIRECT-ab-MediaSync", "pass", "127.0.0.1", port))
        }
        producerLinks.aware = true
        producerLinks.joinable = { it.kind == LinkKind.WIFI_DIRECT }

        val done = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult?.sent shouldBe 4
        done.turboProblem shouldBe null
        consumerLinks.kinds shouldBe listOf(LinkKind.WIFI_AWARE, LinkKind.WIFI_DIRECT)
        consumerLinks.closed.first() shouldStartWith "MediaSync-" // The Wi-Fi Aware link made way.
        producerLinks.joined?.networkName shouldBe "DIRECT-ab-MediaSync"
    }

    @Test
    fun withoutWifiAwareOnBothPhonesTurboGoesOverWifiDirect() = runBlocking<Unit> {
        receiver.hold("screen") // Listening on after the first link closes.
        pairWithApproval()
        consumerLinks.answer = { port, _ ->
            TurboAnswer.Ready(DirectLink("DIRECT-ab-MediaSync", "pass", "127.0.0.1", port))
        }
        producerLinks.aware = true // The receiving phone has none.

        withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }
            .shouldBeInstanceOf<ProducerState.Idle>().turboProblem shouldBe null
        consumerLinks.kinds shouldBe listOf(LinkKind.WIFI_DIRECT)

        // It has Wi-Fi Aware, but it won't start now.
        source.put(FakeMedia("new", Random(9).nextBytes(100_000)))
        consumerLinks.aware = true
        consumerLinks.awareAnswer = { _, _ -> TurboAnswer.Unavailable(TurboUnavailable.FAILED) }
        withTimeout(TIMEOUT) { receiver.state.first { !it.directLinkOpen } }

        val done = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult?.sent shouldBe 1
        done.turboProblem shouldBe null
        consumerLinks.kinds shouldBe listOf(LinkKind.WIFI_DIRECT, LinkKind.WIFI_DIRECT)
    }

    @Test
    fun aDirectLinkThatDropsLeavesTheRestToWifi() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        // The direct link: a relay to the receiving phone that breaks after 500 kB.
        DroppingRelay(target = receiver.state.value.port!!, dropAfterBytes = 500_000).use { relay ->
            consumerLinks.answer = { _, _ ->
                TurboAnswer.Ready(DirectLink("DIRECT-ef-MediaSync", "pass", "127.0.0.1", relay.port))
            }

            val done = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }

            done.shouldBeInstanceOf<ProducerState.Idle>().turboProblem shouldBe TurboProblem.DROPPED
            sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
            producerLinks.left shouldBe true
        }
    }

    @Test
    fun turboReceiveLetsAPhoneAwayFromTheWifiSendDirectly() = runBlocking<Unit> {
        receiver.start()
        pairWithApproval()
        consumerLinks.answer = { port, (name, passphrase) ->
            TurboAnswer.Ready(DirectLink(name, passphrase, "127.0.0.1", port))
        }
        // It joins only a link the receiving phone opened, as a phone would.
        producerLinks.joinable = consumerLinks::isOpen
        offWifi = true

        // Away before ever syncing: it doesn't know the receiving phone's Turbo key yet.
        withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) } shouldBe
            ProducerState.Failed(SyncFailure.TURBO_NOT_SET_UP)

        // A sync on the same Wi-Fi tells it.
        offWifi = false
        withTimeout(TIMEOUT) { producer.syncAndWait() }.shouldBeInstanceOf<ProducerState.Idle>()
        producerPeers.consumer()?.turboKey shouldBe consumerKey
        offWifi = true
        val away = FakeMedia("away", Random(11).nextBytes(150_000))
        source.put(away)

        // Nothing to join until the receiving phone's user starts Turbo receive.
        withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) } shouldBe
            ProducerState.Failed(SyncFailure.TURBO_UNREACHABLE)
        receiver.turbo.startReceive().shouldBeInstanceOf<TurboAnswer.Ready>().link.networkName shouldContain
            DirectLink.NAME_MARKER
        receiver.state.value.turboReceiving shouldBe true

        val done = withTimeout(TIMEOUT) { producer.syncAndWait(turbo = true) }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult?.sent shouldBe 1
        sink.storedContents() shouldBe (photos + away).map { it.bytes.toByteString() }.toSet()
        receiver.turbo.stopReceive()
        receiver.state.value.turboReceiving shouldBe false
        receiver.state.value.directLinkOpen shouldBe false
        withTimeout(TIMEOUT) { while (consumerLinks.closed.isEmpty()) delay(POLL) }
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

/** Wi-Fi Direct as the test wants it: on the phones, a direct link; here, loopback. */
private class FakeDirectLinks : DirectLinks {
    var answer: (
        Int,
        Pair<String, String>,
    ) -> TurboAnswer = { _, _ -> TurboAnswer.Unavailable(TurboUnavailable.UNSUPPORTED) }

    /** This phone has Wi-Fi Aware; its links answer with [awareAnswer]. */
    var aware = false
    var awareAnswer: (Int, Pair<String, String>) -> TurboAnswer = { port, (name, passphrase) ->
        TurboAnswer.Ready(DirectLink(name, passphrase, host = "", port = port, kind = LinkKind.WIFI_AWARE))
    }

    /** Which links this phone can join: any, or only those another phone opened. */
    var joinable: (DirectLink) -> Boolean = { true }
    var joined: DirectLink? = null
    var left = false
    val opened = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    val kinds = java.util.concurrent.CopyOnWriteArrayList<LinkKind>()
    val closed = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun availability() = TurboAvailability.AVAILABLE

    override fun awareAvailable() = aware

    override fun open(port: Int, credentials: Pair<String, String>, kind: LinkKind): TurboAnswer {
        val answers = if (kind == LinkKind.WIFI_AWARE) awareAnswer else answer
        return answers(port, credentials).also {
            if (it is TurboAnswer.Ready) {
                opened += credentials
                kinds += kind
            }
        }
    }

    override fun close(networkName: String) {
        closed += networkName
    }

    override suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink? {
        val joins = joinable(link)
        if (joins) joined = link
        return if (joins) JoinedLink(InetAddress.getByName("127.0.0.1")) else null
    }

    override fun leave() {
        left = true
    }

    /** As a receiving phone: the links it opened and hasn't closed. */
    fun isOpen(link: DirectLink) = (link.networkName to link.passphrase) in opened && link.networkName !in closed
}

/** The receiving phone's Worker and WebRTC, as far as the two phones see them: connections in memory. */
private class FakeInternet(private val producer: () -> DeviceId) : InternetLinks {
    @Volatile
    var allowed = true

    /** Connections go through the relay, as when the phones can't reach each other directly. */
    @Volatile
    var throughRelay = false

    /** On mobile data: what this month's limit leaves; `null` on Wi-Fi. */
    @Volatile
    var mobileDataLeft: Long? = null
    val mobileDataCounted = java.util.concurrent.atomic.AtomicLong()

    override fun mobileDataLeft() = mobileDataLeft

    override fun countMobileData(bytes: Long) {
        mobileDataCounted.addAndGet(bytes)
    }
    val connects = java.util.concurrent.atomic.AtomicInteger()
    val listening = ConcurrentHashMap<String, Pair<Map<DeviceId, ByteString>, (PeerConnection) -> Unit>>()

    override fun allowed() = allowed

    override fun connect(consumer: DeviceId, worker: String, pairSecret: ByteString): PeerConnection {
        connects.incrementAndGet()
        val (pairs, serve) = listening[worker] ?: throw java.io.IOException("The receiving phone isn't online")
        val sender = producer()
        if (pairs[sender] != pairSecret) throw java.io.IOException("Not sealed with this pair's secret")
        val connection = ConnectionPair(sender, consumer)
        kotlin.concurrent.thread(isDaemon = true) { serve(connection.consumerEnd) }
        val end = connection.producerEnd
        return if (!throughRelay) {
            end
        } else {
            object : PeerConnection by end, io.github.danyk20.mediasync.transport.rendezvous.RelayCounter {
                override fun relayedBytes() = 0L

                override fun throughRelay() = true
            }
        }
    }

    override fun listen(worker: String, pairs: Map<DeviceId, ByteString>, serve: (PeerConnection) -> Unit): Closeable {
        listening[worker] = pairs to serve
        return Closeable { listening.remove(worker) }
    }

    /** The Worker's meter, as asked: by which Worker, for which pair. */
    val meterAsked = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DeviceId>>()

    override fun relayUsage(worker: String, producer: DeviceId): RelayUsage {
        meterAsked += worker to producer
        return RelayUsage(312_000_000_000, 950_000_000_000, 1_000_000_000_000, true, null)
    }
}

/** Forwards connections to [target] on loopback, and breaks every one once [dropAfterBytes] went through. */
private class DroppingRelay(private val target: Int, private val dropAfterBytes: Long) : Closeable {
    private val server = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
    val port: Int = server.localPort

    init {
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = java.net.Socket(InetAddress.getLoopbackAddress(), target)
                sockets += listOf(client, upstream)
                pump(client, upstream, dropAfterBytes)
                pump(upstream, client, Long.MAX_VALUE)
            }
        }
    }

    private fun pump(from: java.net.Socket, to: java.net.Socket, limit: Long) =
        kotlin.concurrent.thread(isDaemon = true) {
            val buffer = ByteArray(BUFFER)
            var total = 0L
            runCatching {
                while (total < limit) {
                    val read = from.getInputStream().read(buffer)
                    if (read < 0) break
                    to.getOutputStream().write(buffer, 0, read)
                    total += read
                }
            }
            from.close()
            to.close()
        }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** No network at all: every connection fails, as off the receiving phone's Wi-Fi. */
private object NoNetwork : SocketFactory() {
    private fun none(): Nothing = throw java.io.IOException("Not on that network")

    override fun createSocket(): java.net.Socket = none()

    override fun createSocket(host: String?, port: Int): java.net.Socket = none()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): java.net.Socket =
        none()

    override fun createSocket(host: InetAddress?, port: Int): java.net.Socket = none()

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): java.net.Socket = none()
}

/** The same media under another key, with other bytes, so it counts as new. */
private fun FakeMedia.copyAs(key: String) =
    FakeMedia(key, bytes + key.encodeToByteArray(), displayName, mimeType, meta, shared)
