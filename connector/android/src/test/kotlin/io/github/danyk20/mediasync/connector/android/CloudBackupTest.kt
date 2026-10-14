/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppFactory
import io.github.danyk20.mediasync.connector.testing.contentId
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.ReceivedItemEntity
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, MediaSyncDatabase::class.java).allowMainThreadQueries().build()
    private val apps = FakeDeviceAppFactory()
    private val registry = ConnectorRegistry(mapOf("test-app" to apps))
    private val settings = FakeSettingsRepository(initialRole = DeviceRole.CONSUMER)

    /** 10:00 in Zurich: today started 10 hours ago. */
    private val clock = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logged = mutableListOf<String>()
    private val accountId = AccountId("test-app")
    private val backup = CloudBackup(
        registry,
        RoomAccountStore(database.backupDao()),
        database.backupDao(),
        database.receivedItemDao(),
        settings,
        BackupOrchestrator(RoomBackupLedger(database.backupDao()) { ByteArrayInputStream(ByteArray(0)) }, clock),
        clock,
        scope,
    )

    init {
        Diagnostics.install { area, message -> synchronized(logged) { logged += "${area.label}: $message" } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        Diagnostics.install { _, _ -> }
    }

    @Test
    fun anAppThatBacksUpIsSetUpOnceAndKeepsItsLedger() = runBlocking<Unit> {
        backup.deviceApps shouldBe listOf(apps)
        backup.overviews().first().shouldBeEmpty()

        backup.connect(apps)
        backup.connect(apps) // Again, later: the same account.

        val account = ConnectorAccount(accountId, ConnectorId("test-app"), "Test cloud", clock.millis())
        backup.overviews().first() shouldBe listOf(BackupOverview(account))
        logged shouldContain "backup: Backup to test-app set up"

        backup.disconnect(accountId)
        backup.overviews().first().shouldBeEmpty()
        logged shouldContain "backup: Backup to test-app turned off"
    }

    @Test
    fun whatArrivesIsHandedOverAtOnceAndCountedForToday() = runBlocking<Unit> {
        receive(1)
        receive(2) // Not on the phone any more by the time its turn comes.
        apps.connector.onPhone += location(1)
        backup.connect(apps)

        backup.start()
        backup.start() // Once is enough.

        // Each count comes from its own query, which catch up one after another.
        val expected = BackupOverview(backup.overviews().first().single().account, handedOver = 1, today = 1)
        awaitFirst(backup.overviews()) { it == listOf(expected) }
        database.backupDao().receipts(accountId.value, "SKIPPED").map { it.contentId } shouldBe listOf(contentId(2).hex)

        apps.connector.onPhone += location(3)
        receive(3)
        awaitFirst(backup.overviews()) { it == listOf(expected.copy(handedOver = 2, today = 2)) }
    }

    @Test
    fun aSendingPhoneDoesntBackUp() = runBlocking<Unit> {
        settings.setRole(DeviceRole.PRODUCER)
        receive(1)
        apps.connector.onPhone += location(1)
        backup.connect(apps)

        backup.start()

        // Give it a moment to do (nothing) wrongly.
        Thread.sleep(QUIET_MILLIS)
        backup.overviews().first().single().waiting shouldBe 1
    }

    @Test
    fun theAppBuildsItWithItsOwnScope() {
        val injected = CloudBackup(
            registry,
            RoomAccountStore(database.backupDao()),
            database.backupDao(),
            database.receivedItemDao(),
            settings,
            BackupOrchestrator(RoomBackupLedger(database.backupDao()) { ByteArrayInputStream(ByteArray(0)) }, clock),
            clock,
        )

        injected.deviceApps shouldBe listOf(apps)
    }

    private fun receive(n: Int) = database.receivedItemDao().insert(
        ReceivedItemEntity(
            producerId = "producer",
            fingerprint = "fingerprint-$n",
            contentId = contentId(n).hex,
            displayName = "IMG_$n.jpg",
            mimeType = "image/jpeg",
            size = 100,
            capturedAtMillis = null,
            utcOffsetMinutes = null,
            album = null,
            favorite = false,
            location = location(n),
            receivedAtMillis = n.toLong(),
        ),
    )

    private fun location(n: Int) = "content://media/external_primary/images/media/$n"

    private suspend fun <T> awaitFirst(flow: Flow<T>, condition: (T) -> Boolean): T =
        withTimeout(TIMEOUT_MILLIS) { flow.first(condition) }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val QUIET_MILLIS = 300L
    }
}
