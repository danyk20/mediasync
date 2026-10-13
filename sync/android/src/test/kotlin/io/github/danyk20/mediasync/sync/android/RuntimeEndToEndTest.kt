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
    private val sink = FakeMediaSink()
    private val consumerIdentity = testIdentity("Pixel 2 XL")
    private val receiver = ConsumerReceiver(
        identity = { consumerIdentity },
        peers = consumerPeers,
        ledger = RoomReceivedLedger(consumerDb.receivedItemDao()),
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
        val link = receiver.openPairing().shouldNotBeNull()
        // The consumer's user approves the request when it appears.
        scope.launch { receiver.pendingApproval.first { it != null }?.accept() }

        val paired = producer.pair(link)

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

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MediaSyncDatabase::class.java,
    ).allowMainThreadQueries().build()

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
