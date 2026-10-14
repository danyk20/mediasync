/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.ReceivedItem
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgersTest {

    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MediaSyncDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val consumer = deviceId(2)
    private val producer = deviceId(1)
    private val photo = FakeMedia("photo", "jpeg bytes".encodeToByteArray())
    private val contentId = "sha".encodeUtf8().sha256()

    @After
    fun tearDown() = database.close()

    @Test
    fun sentLedgerRemembersPerConsumer() {
        val ledger = RoomSentLedger(database.sentItemDao())

        ledger.markSent(consumer, photo.descriptor.fingerprint, contentId)

        ledger.sentFingerprints(consumer) shouldBe setOf(photo.descriptor.fingerprint)
        ledger.sentFingerprints(deviceId(3)).shouldBeEmpty()
    }

    @Test
    fun markingTheSameItemTwiceKeepsOneEntry() {
        val ledger = RoomSentLedger(database.sentItemDao())

        ledger.markSent(consumer, photo.descriptor.fingerprint, contentId)
        ledger.markSent(consumer, photo.descriptor.fingerprint, contentId)

        ledger.sentFingerprints(consumer).size shouldBe 1
    }

    @Test
    fun receivedLedgerFindsItemsByFingerprintWithMetadata() = runTest {
        val ledger = RoomReceivedLedger(database.receivedItemDao())
        ledger.contentIdOf(photo.descriptor.fingerprint).shouldBeNull()

        ledger.record(ReceivedItem(producer, photo.descriptor, contentId, "content://media/1"))

        ledger.contentIdOf(photo.descriptor.fingerprint) shouldBe contentId
        ledger.observeCount().first() shouldBe 1
        ledger.observeCountFrom(producer).first() shouldBe 1
        database.query("SELECT capturedAtMillis FROM received_items", null).use { stored ->
            stored.moveToFirst() shouldBe true
            stored.getLong(0) shouldBe photo.descriptor.meta.capturedAtMillis
        }
    }

    @Test
    fun peerStoreRegistersAndForgetsPeers() = runTest {
        val peers = PeerStore(database.peerDao())
        peers.isPaired(producer) shouldBe false

        peers.register(PairedPeer(consumer, "Pixel 2 XL", DeviceRole.CONSUMER), address = "192.168.1.20:47420")
        peers.register(PairedPeer(producer, "Anna's Pixel", DeviceRole.PRODUCER))

        peers.isPaired(producer) shouldBe true
        peers.consumer()?.name shouldBe "Pixel 2 XL"
        peers.consumer()?.lastAddress shouldBe "192.168.1.20:47420"
        peers.observeAll().first().map { it.name } shouldBe listOf("Pixel 2 XL", "Anna's Pixel")

        peers.recordAddress(consumer, "192.168.1.30:47420")
        peers.consumer()?.lastAddress shouldBe "192.168.1.30:47420"

        peers.forget(producer)
        peers.isPaired(producer) shouldBe false
    }
}
