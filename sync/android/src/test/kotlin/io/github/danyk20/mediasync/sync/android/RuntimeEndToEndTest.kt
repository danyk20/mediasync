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
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.transport.lan.testIdentity
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.Closeable
import java.net.InetAddress
import javax.net.SocketFactory
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val receiver = ConsumerReceiver(
        identity = { consumerIdentity },
        peers = consumerPeers,
        ledger = received,
        sink = sink,
        device = { LocalDevice("Pixel 2 XL", "test") },
        addresses = loopback,
        discovery = noDiscovery,
        scope = scope,
        preferredPort = 0,
    )

    // The sending phone.
    private val producerDb = database()
    private val photos = List(4) { FakeMedia("photo$it", Random(it).nextBytes(300_000 + it)) }
    private val source = FakeMediaSource(photos)
    private val producerIdentity = testIdentity("Anna's Pixel")
    private val producer = ProducerSync(
        identity = { producerIdentity },
        peers = PeerStore(producerDb.peerDao()),
        ledger = RoomSentLedger(producerDb.sentItemDao()),
        source = source,
        device = { LocalDevice("Anna's Pixel", "test") },
        discovery = noDiscovery,
        sockets = { SocketFactory.getDefault() },
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
    fun pairByLinkThenSyncNow() = runBlocking<Unit> {
        receiver.start()

        val paired = pairWithApproval()

        paired shouldBe PairingResult.Paired("Pixel 2 XL")
        consumerPeers.isPaired(producerIdentity.id) shouldBe true
        receiver.state.value.lastPaired?.name shouldBe "Anna's Pixel"
        producer.pendingCount() shouldBe 4

        producer.syncNow()
        val done = withTimeout(TIMEOUT) { producer.state.first { it is ProducerState.Idle && it.lastResult != null } }

        done.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(sent = 4, bytesSent = photos.sumOf { it.bytes.size.toLong() })
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
        producer.pendingCount() shouldBe 0

        // Nothing new: the next sync transfers nothing.
        producer.syncNow()
        val again = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it.lastResult != done.lastResult }
        }
        again.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe SyncResult()
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
        producer.pendingCount() shouldBe 0

        val newPhoto = FakeMedia("new", Random(photos.size).nextBytes(200_000))
        source.put(newPhoto)
        producer.syncNow()
        val again = withTimeout(TIMEOUT) {
            producer.state.first { it is ProducerState.Idle && it != first }
        }

        again.shouldBeInstanceOf<ProducerState.Idle>().lastResult shouldBe
            SyncResult(sent = 1, bytesSent = newPhoto.bytes.size.toLong())
        sink.storedContents() shouldBe (photos + newPhoto).map { it.bytes.toByteString() }.toSet()
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
    }
}
