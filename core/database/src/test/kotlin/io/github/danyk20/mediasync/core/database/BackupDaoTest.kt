/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The backup ledger's tables (docs/PLAN.md §9.4). */
@RunWith(RobolectricTestRunner::class)
class BackupDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MediaSyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val dao = database.backupDao()
    private val received = database.receivedItemDao()

    @After
    fun tearDown() = database.close()

    @Test
    fun accountsAreKeptInTheOrderTheyWereAdded() = runTest {
        dao.upsertAccount(account("second", createdAt = 2))
        dao.upsertAccount(account("first", createdAt = 1))
        dao.observeAccounts().first().map { it.id } shouldBe listOf("first", "second")

        dao.deleteAccount("first")
        dao.observeAccounts().first().map { it.id } shouldBe listOf("second")
    }

    @Test
    fun whatAnAccountStillNeedsIsWhatItHasNoReceiptFor() = runTest {
        (1..4).forEach { received.insert(item(it)) }
        received.insert(item(2)) // The same content again, from another phone.
        dao.upsertReceipts(listOf(receipt(1, "app"), receipt(3, "other")))

        dao.pending("app", limit = 10).map { it.contentId } shouldBe listOf(hex(2), hex(3), hex(4), hex(2))
        dao.pending("app", limit = 2).map { it.contentId } shouldBe listOf(hex(2), hex(3))
        dao.observeWaiting("app").first() shouldBe 3
        dao.observeWaiting("other").first() shouldBe 3
    }

    @Test
    fun receiptsAreReplacedAndCountedByState() = runTest {
        dao.upsertReceipts((1..3).map { receipt(it, "app", backedUpAt = it * 10L) })
        dao.upsertReceipts(listOf(receipt(1, "app", state = "REMOVED_BY_APP", backedUpAt = 10, checkedAt = 99)))
        dao.upsertReceipts(listOf(receipt(4, "app", state = "SKIPPED", backedUpAt = 40)))

        dao.receipts("app", "HANDED_OVER").map { it.contentId } shouldContainExactlyInAnyOrder listOf(hex(2), hex(3))
        dao.receipts("app", "REMOVED_BY_APP").single().checkedAtMillis shouldBe 99
        dao.receipts("other", "HANDED_OVER").shouldBeEmpty()
        dao.observeStateCounts("app").first() shouldContainExactlyInAnyOrder listOf(
            StateCount("HANDED_OVER", 2),
            StateCount("REMOVED_BY_APP", 1),
            StateCount("SKIPPED", 1),
        )
        // Items 2 and 3; skipped ones don't count.
        dao.observeBackedUpSince("app", sinceMillis = 20).first() shouldBe 2
    }

    private fun account(id: String, createdAt: Long) =
        ConnectorAccountEntity(id, "google-photos-app", "Photos", settingsJson = "{}", createdAtMillis = createdAt)

    private fun receipt(
        n: Int,
        account: String,
        state: String = "HANDED_OVER",
        backedUpAt: Long = 0,
        checkedAt: Long? = null,
    ) = BackupReceiptEntity(hex(n), account, "content://media/external/images/media/$n", state, backedUpAt, checkedAt)

    private fun item(n: Int) = ReceivedItemEntity(
        producerId = "producer",
        fingerprint = "fingerprint-$n",
        contentId = hex(n),
        displayName = "IMG_$n.jpg",
        mimeType = "image/jpeg",
        size = 100,
        capturedAtMillis = null,
        utcOffsetMinutes = null,
        album = null,
        favorite = false,
        location = "content://media/external/images/media/$n",
        receivedAtMillis = n.toLong(),
    )

    private fun hex(n: Int) = n.toString().padStart(64, '0')
}
