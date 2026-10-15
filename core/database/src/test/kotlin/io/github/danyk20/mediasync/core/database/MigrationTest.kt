/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every schema version upgrades without losing pairings or ledgers (schemas in `core/database/schemas`). */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MediaSyncDatabase::class.java)

    private val consumer = deviceId(2)
    private val fingerprint = "photo".encodeUtf8().sha256()

    @Test
    fun version1PairingsAndLedgersSurviveAndHaveNoScheduleYet() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO peers (deviceId, name, role, pairedAtMillis, lastAddress, lastSeenMillis) " +
                    "VALUES ('${consumer.hex}', 'Pixel 2 XL', 'CONSUMER', 1, '192.168.1.20:47420', 2)",
            )
            db.execSQL(
                "INSERT INTO sent_items (consumerId, fingerprint, contentId, sentAtMillis) " +
                    "VALUES ('${consumer.hex}', '${fingerprint.hex()}', '${fingerprint.hex()}', 3)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 2, true).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MediaSyncDatabase::class.java,
            NAME,
        ).allowMainThreadQueries().build()
        try {
            val peers = PeerStore(database.peerDao())
            val paired = peers.consumer()!!
            paired.name shouldBe "Pixel 2 XL"
            paired.lastAddress shouldBe "192.168.1.20:47420"
            paired.schedule.shouldBeNull()
            RoomSentLedger(database.sentItemDao()).sentFingerprints(consumer) shouldBe setOf(fingerprint)

            val everySix = SyncSchedule.EveryHours(6, LocalTime.of(3, 0))
            peers.recordConsumer(consumer, ConsumerFacts(schedule = everySix))
            peers.consumer()!!.schedule shouldBe everySix
        } finally {
            database.close()
        }
    }

    @Test
    fun version2PairingsSurviveAndHaveNoTurboKeyYet() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO peers (deviceId, name, role, pairedAtMillis, lastAddress, lastSeenMillis, schedule) " +
                    "VALUES ('${consumer.hex}', 'Pixel 2 XL', 'CONSUMER', 1, '192.168.1.20:47420', 2, 'charging')",
            )
        }

        helper.runMigrationsAndValidate(NAME, 3, true).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MediaSyncDatabase::class.java,
            NAME,
        ).allowMainThreadQueries().build()
        try {
            val peers = PeerStore(database.peerDao())
            peers.consumer()!!.name shouldBe "Pixel 2 XL"
            peers.consumer()!!.turboKey.shouldBeNull()

            val key = ByteArray(32) { 5 }.toByteString()
            peers.recordConsumer(consumer, ConsumerFacts(schedule = null, turboKey = key))
            // An older consumer that didn't say: the key known stays.
            peers.recordConsumer(consumer, ConsumerFacts(schedule = null, turboKey = null))
            peers.consumer()!!.turboKey shouldBe key
        } finally {
            database.close()
        }
    }

    @Test
    fun version3ReceivedItemsSurviveAndAreWaitingForBackup() = runTest {
        helper.createDatabase(NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO received_items (producerId, fingerprint, contentId, displayName, mimeType, size, " +
                    "capturedAtMillis, utcOffsetMinutes, album, favorite, location, receivedAtMillis) " +
                    "VALUES ('${consumer.hex}', '${fingerprint.hex()}', '${fingerprint.hex()}', 'PXL_1.jpg', " +
                    "'image/jpeg', 100, 1, 120, 'Camera', 0, 'content://media/external/images/media/1', 2)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 4, true).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MediaSyncDatabase::class.java,
            NAME,
        ).allowMainThreadQueries().build()
        try {
            val backups = database.backupDao()
            backups.observeAccounts().first().shouldBeEmpty()
            backups.pending("google-photos-app", limit = 10).map { it.contentId } shouldBe listOf(fingerprint.hex())
        } finally {
            database.close()
        }
    }

    @Test
    fun version4PairingsSurviveAndGetAPairSecretWhenFirstNeeded() = runTest {
        val producer = deviceId(3)
        helper.createDatabase(NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO peers (deviceId, name, role, pairedAtMillis) " +
                    "VALUES ('${producer.hex}', 'Anna''s Pixel', 'PRODUCER', 1)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 5, true).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MediaSyncDatabase::class.java,
            NAME,
        ).allowMainThreadQueries().build()
        try {
            val peers = PeerStore(database.peerDao())
            peers.observeAll().first().single().pairSecret.shouldBeNull()

            val secret = peers.pairSecret(producer)!!

            secret.size shouldBe 32
            peers.pairSecret(producer) shouldBe secret // Kept.
            peers.pairSecret(deviceId(9)).shouldBeNull() // Not paired.
            // The producer's side keeps what it was told.
            peers.recordConsumer(consumer, ConsumerFacts(pairSecret = secret))
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
