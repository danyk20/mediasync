/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.testing.contentId
import io.github.danyk20.mediasync.core.database.BackupReceiptEntity
import io.github.danyk20.mediasync.core.database.ConnectorAccountEntity
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.ReceivedItemEntity
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LedgerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, MediaSyncDatabase::class.java).allowMainThreadQueries().build()
    private val dao = database.backupDao()
    private val opened = mutableListOf<String>()
    private val ledger = RoomBackupLedger(dao) { location ->
        opened += location
        ByteArrayInputStream(location.encodeToByteArray())
    }
    private val accounts = RoomAccountStore(dao)
    private val app = AccountId("google-photos-app")

    @After
    fun tearDown() = database.close()

    @Test
    fun receivedItemsWaitUntilTheyHaveAReceipt() = runTest {
        database.receivedItemDao().insert(received(1, capturedAt = 42, album = "Camera"))
        database.receivedItemDao().insert(received(2))

        val pending = ledger.pending(app, limit = 10)

        pending.map { it.contentId } shouldBe listOf(contentId(1), contentId(2))
        with(pending.first()) {
            displayName shouldBe "IMG_1.jpg"
            mimeType shouldBe "image/jpeg"
            size shouldBe 100
            location shouldBe location(1)
            capturedAtMillis shouldBe 42
            album shouldBe "Camera"
            opened.shouldBeEmpty() // Bytes are only read when a connector needs them.
            content.open().use { it.readBytes() } shouldBe location(1).encodeToByteArray()
        }

        ledger.record(listOf(receipt(1, BackupState.HANDED_OVER)))
        ledger.pending(app, limit = 10).map { it.contentId } shouldBe listOf(contentId(2))
    }

    @Test
    fun receiptsAreFoundByState() = runTest {
        ledger.record(listOf(receipt(1, BackupState.HANDED_OVER), receipt(2, BackupState.HANDED_OVER)))
        ledger.record(listOf(receipt(1, BackupState.REMOVED_BY_APP, checkedAt = 9)))

        ledger.receipts(app, BackupState.HANDED_OVER) shouldBe listOf(receipt(2, BackupState.HANDED_OVER))
        ledger.receipts(app, BackupState.REMOVED_BY_APP) shouldBe
            listOf(receipt(1, BackupState.REMOVED_BY_APP, checkedAt = 9))
    }

    @Test
    fun rowsThisVersionCantReadAreLeftAlone() = runTest {
        database.receivedItemDao().insert(received(1).copy(contentId = "not-a-hash"))
        dao.upsertReceipts(
            listOf(
                BackupReceiptEntity(contentId(2).hex, app.value, "x", "ARCHIVED_ON_MARS", 0, null),
                BackupReceiptEntity("not-a-hash", app.value, "x", "HANDED_OVER", 0, null),
            ),
        )
        dao.upsertAccount(ConnectorAccountEntity("broken", "Not An Id", "?", "{}", 0))
        dao.upsertAccount(ConnectorAccountEntity("garbled", "google-photos-app", "?", "{not json", 0))

        ledger.pending(app, limit = 10).shouldBeEmpty()
        ledger.receipts(app, BackupState.HANDED_OVER).shouldBeEmpty()
        accounts.observe().first().shouldBeEmpty()
    }

    @Test
    fun accountsKeepTheirSettings() = runTest {
        val account = ConnectorAccount(
            app,
            ConnectorId("google-photos-app"),
            "Google Photos",
            createdAtMillis = 5,
            settings = mapOf("quality" to "storage-saver", "note" to "a \"quoted\" value"),
        )

        accounts.add(account)
        accounts.observe().first() shouldBe listOf(account)

        accounts.remove(app)
        accounts.observe().first().shouldBeEmpty()
    }

    @Test
    fun storedItemsAreReadThroughTheirContentUri() {
        val uri = Uri.parse(location(3))
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))

        ContentResolverOpener(context).open(location(3)).use { it.readBytes() } shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun appsCountAsInstalledWhileEnabled() {
        val apps = PackageManagerApps(context)
        apps.isInstalled(PHOTOS).shouldBeFalse()

        install(PHOTOS, enabled = true)
        install("com.example.disabled", enabled = false)

        apps.isInstalled(PHOTOS).shouldBeTrue()
        apps.isInstalled("com.example.disabled").shouldBeFalse()
    }

    private fun install(name: String, enabled: Boolean) = shadowOf(context.packageManager).installPackage(
        PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.enabled = enabled
            }
        },
    )

    private fun receipt(n: Int, state: BackupState, checkedAt: Long? = null) =
        BackupReceipt(contentId(n), app, location(n), state, backedUpAtMillis = 1, checkedAtMillis = checkedAt)

    private fun received(n: Int, capturedAt: Long? = null, album: String? = null) = ReceivedItemEntity(
        producerId = "producer",
        fingerprint = "fingerprint-$n",
        contentId = contentId(n).hex,
        displayName = "IMG_$n.jpg",
        mimeType = "image/jpeg",
        size = 100,
        capturedAtMillis = capturedAt,
        utcOffsetMinutes = null,
        album = album,
        favorite = false,
        location = location(n),
        receivedAtMillis = n.toLong(),
    )

    private fun location(n: Int) = "content://media/external_primary/images/media/$n"

    private companion object {
        const val PHOTOS = "com.google.android.apps.photos"
    }
}
