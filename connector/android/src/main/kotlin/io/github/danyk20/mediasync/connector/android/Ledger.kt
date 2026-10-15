/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.core.database.BackupDao
import io.github.danyk20.mediasync.core.database.BackupReceiptEntity
import io.github.danyk20.mediasync.core.database.ConnectorAccountEntity
import io.github.danyk20.mediasync.core.database.ReceivedItemEntity
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

/** Opens a stored item's bytes by its location, such as a MediaStore content URI. */
fun interface ContentOpener {
    fun open(location: String): InputStream
}

class ContentResolverOpener @Inject constructor(@ApplicationContext private val context: Context) : ContentOpener {
    override fun open(location: String): InputStream =
        context.contentResolver.openInputStream(location.toUri()) ?: throw FileNotFoundException("Can't open it")
}

/** The backup ledger (docs/PLAN.md §9.4) in the app's database, next to the record of everything received. */
@Singleton
class RoomBackupLedger @Inject constructor(private val dao: BackupDao, private val opener: ContentOpener) :
    BackupLedger {

    override suspend fun pending(account: AccountId, limit: Int): List<BackupItem> =
        dao.pending(account.value, limit).mapNotNull { it.toItem() }

    override suspend fun record(receipts: List<BackupReceipt>) = dao.upsertReceipts(receipts.map { it.toEntity() })

    override suspend fun receipts(account: AccountId, state: BackupState): List<BackupReceipt> =
        dao.receipts(account.value, state.name).mapNotNull { it.toReceipt() }

    private fun ReceivedItemEntity.toItem(): BackupItem? {
        val id = contentIdOrNull(contentId) ?: return null
        return BackupItem(
            contentId = id,
            displayName = displayName,
            mimeType = mimeType,
            size = size,
            location = location,
            capturedAtMillis = capturedAtMillis,
            album = album,
            content = { opener.open(location) },
        )
    }
}

/** The places this phone backs up to. */
interface AccountStore {
    fun observe(): Flow<List<ConnectorAccount>>

    /** Adds [account], or replaces the one with its id. */
    suspend fun add(account: ConnectorAccount)

    suspend fun remove(id: AccountId)
}

@Singleton
class RoomAccountStore @Inject constructor(private val dao: BackupDao) : AccountStore {
    override fun observe(): Flow<List<ConnectorAccount>> =
        dao.observeAccounts().map { accounts -> accounts.mapNotNull { it.toAccount() } }

    override suspend fun add(account: ConnectorAccount) = dao.upsertAccount(
        ConnectorAccountEntity(
            id = account.id.value,
            connectorId = account.connectorId.value,
            displayName = account.displayName,
            settingsJson = JSONObject(account.settings).toString(),
            createdAtMillis = account.createdAtMillis,
        ),
    )

    override suspend fun remove(id: AccountId) = dao.deleteAccount(id.value)

    /** `null` for a row this version can't read, e.g. one written by a newer version: it's left alone. */
    private fun ConnectorAccountEntity.toAccount(): ConnectorAccount? = try {
        val json = JSONObject(settingsJson)
        ConnectorAccount(
            id = AccountId(id),
            connectorId = ConnectorId(connectorId),
            displayName = displayName,
            createdAtMillis = createdAtMillis,
            settings = json.keys().asSequence().associateWith { json.getString(it) },
        )
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JSONException) {
        null
    }
}

private fun BackupReceipt.toEntity() = BackupReceiptEntity(
    contentId = contentId.hex,
    accountId = accountId.value,
    remoteId = remoteId,
    state = state.name,
    backedUpAtMillis = backedUpAtMillis,
    checkedAtMillis = checkedAtMillis,
)

/** `null` for a row this version can't read. */
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
