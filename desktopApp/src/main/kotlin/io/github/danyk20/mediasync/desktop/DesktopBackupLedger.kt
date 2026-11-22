/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.SkipReason
import io.github.danyk20.mediasync.core.database.BackupDao
import io.github.danyk20.mediasync.core.database.BackupReceiptEntity
import io.github.danyk20.mediasync.core.database.ReceivedItemEntity
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock

/**
 * The backup ledger (docs/PLAN.md §9.4) on a computer: the Room tables the phone uses, with received items read from
 * the inbox. An item whose file is no longer there (the user removed it) gets a skipped receipt instead of failing
 * every later run.
 */
class DesktopBackupLedger(private val dao: BackupDao, private val clock: Clock) : BackupLedger {

    override suspend fun pending(account: AccountId, limit: Int): List<BackupItem> {
        while (true) {
            val rows = dao.pending(account.value, limit)
            if (rows.isEmpty()) return emptyList()
            val (here, gone) = rows.partition { fileOf(it.location)?.let(Files::isRegularFile) == true }
            if (gone.isNotEmpty()) dao.upsertReceipts(gone.map { it.skipped(account) })
            val items = here.mapNotNull { it.toItem() }
            if (items.isNotEmpty()) return items
            // Only unreadable rows in this batch: they have receipts now, so the next batch is a different one.
        }
    }

    override suspend fun record(receipts: List<BackupReceipt>) = dao.upsertReceipts(receipts.map { it.toEntity() })

    override suspend fun receipts(account: AccountId, state: BackupState): List<BackupReceipt> =
        dao.receipts(account.value, state.name).mapNotNull { it.toReceipt() }

    private fun ReceivedItemEntity.skipped(account: AccountId) = BackupReceiptEntity(
        contentId = contentId,
        accountId = account.value,
        remoteId = SkipReason.NOT_ON_PHONE.name,
        state = BackupState.SKIPPED.name,
        backedUpAtMillis = clock.millis(),
        checkedAtMillis = null,
    )

    private fun ReceivedItemEntity.toItem(): BackupItem? {
        val id = contentIdOrNull(contentId) ?: return null
        val file = fileOf(location) ?: return null
        return BackupItem(
            contentId = id,
            displayName = displayName,
            mimeType = mimeType,
            size = size,
            location = location,
            capturedAtMillis = capturedAtMillis,
            album = album,
            content = { Files.newInputStream(file) },
        )
    }
}

internal fun fileOf(location: String): Path? = try {
    Paths.get(location)
} catch (_: InvalidPathException) {
    null
}

private fun BackupReceipt.toEntity() = BackupReceiptEntity(
    contentId = contentId.hex,
    accountId = accountId.value,
    remoteId = remoteId,
    state = state.name,
    backedUpAtMillis = backedUpAtMillis,
    checkedAtMillis = checkedAtMillis,
)

private fun BackupReceiptEntity.toReceipt(): BackupReceipt? {
    val id = contentIdOrNull(contentId) ?: return null
    val state = BackupState.entries.firstOrNull { it.name == state } ?: return null
    return BackupReceipt(id, AccountId(accountId), remoteId, state, backedUpAtMillis, checkedAtMillis)
}

private fun contentIdOrNull(hex: String): ContentId? = try {
    ContentId(hex)
} catch (_: IllegalArgumentException) {
    null
}
