/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Blocking methods are called by the sync engine on I/O threads; Flow methods feed the UI.

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY pairedAtMillis")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    fun get(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE role = :role ORDER BY pairedAtMillis DESC")
    fun withRole(role: String): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE role = :role ORDER BY pairedAtMillis DESC")
    fun observeWithRole(role: String): Flow<List<PeerEntity>>

    @Query("DELETE FROM peers WHERE role = :role AND deviceId != :keep")
    fun deleteOthersWithRole(role: String, keep: String)

    @Upsert
    fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET lastAddress = :address, lastSeenMillis = :seenAtMillis WHERE deviceId = :deviceId")
    fun recordAddress(deviceId: String, address: String, seenAtMillis: Long)

    @Query("UPDATE peers SET schedule = :schedule WHERE deviceId = :deviceId")
    fun recordSchedule(deviceId: String, schedule: String?)

    /** Keeps what's given; a `null` leaves what's stored. */
    @Query(
        "UPDATE peers SET turboKey = COALESCE(:turboKey, turboKey), pairSecret = COALESCE(:pairSecret, pairSecret), " +
            "rendezvousUrl = COALESCE(:rendezvousUrl, rendezvousUrl) WHERE deviceId = :deviceId",
    )
    fun recordFacts(deviceId: String, turboKey: String?, pairSecret: String?, rendezvousUrl: String?)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    fun delete(deviceId: String)
}

@Dao
interface SentItemDao {
    @Query("SELECT fingerprint FROM sent_items WHERE consumerId = :consumerId")
    fun fingerprintsFor(consumerId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: SentItemEntity)

    @Query("SELECT MAX(sentAtMillis) FROM sent_items WHERE consumerId = :consumerId")
    fun observeLastSentAt(consumerId: String): Flow<Long?>
}

@Dao
interface ReceivedItemDao {
    @Query("SELECT contentId FROM received_items WHERE fingerprint = :fingerprint LIMIT 1")
    fun contentIdOf(fingerprint: String): String?

    @Insert
    fun insert(item: ReceivedItemEntity)

    @Query("SELECT COUNT(*) FROM received_items")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM received_items WHERE producerId = :producerId")
    fun observeCountFrom(producerId: String): Flow<Int>
}

@Dao
interface PartialTransferDao {
    @Query("SELECT * FROM partial_transfers WHERE producerId = :producerId AND fingerprint = :fingerprint")
    fun get(producerId: String, fingerprint: String): PartialTransferEntity?

    @Upsert
    fun upsert(partial: PartialTransferEntity)

    @Query("DELETE FROM partial_transfers WHERE producerId = :producerId AND fingerprint = :fingerprint")
    fun delete(producerId: String, fingerprint: String)
}

@Dao
interface MediaFingerprintDao {
    @Query("SELECT * FROM media_fingerprints WHERE mediaStoreUri = :uri")
    fun get(uri: String): MediaFingerprintEntity?

    @Upsert
    fun upsert(entry: MediaFingerprintEntity)
}

@Dao
interface LeftOutItemDao {
    @Query("SELECT fingerprint FROM left_out_items")
    fun fingerprints(): List<String>

    @Query("SELECT fingerprint FROM left_out_items")
    fun observeFingerprints(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<LeftOutItemEntity>)

    @Query("DELETE FROM left_out_items WHERE fingerprint IN (:fingerprints)")
    suspend fun delete(fingerprints: List<String>)
}

/** How many receipts an account has in one state. */
data class StateCount(val state: String, val count: Int)

@Dao
interface BackupDao {
    @Query("SELECT * FROM connector_accounts ORDER BY createdAtMillis")
    fun observeAccounts(): Flow<List<ConnectorAccountEntity>>

    @Upsert
    suspend fun upsertAccount(account: ConnectorAccountEntity)

    @Query("DELETE FROM connector_accounts WHERE id = :id")
    suspend fun deleteAccount(id: String)

    /** Received items without a receipt for the account yet, oldest first. */
    @Query(
        "SELECT * FROM received_items AS item WHERE NOT EXISTS (SELECT 1 FROM backup_receipts AS receipt " +
            "WHERE receipt.accountId = :accountId AND receipt.contentId = item.contentId) " +
            "ORDER BY item.rowId LIMIT :limit",
    )
    suspend fun pending(accountId: String, limit: Int): List<ReceivedItemEntity>

    @Upsert
    suspend fun upsertReceipts(receipts: List<BackupReceiptEntity>)

    @Query("SELECT * FROM backup_receipts WHERE accountId = :accountId AND state = :state")
    suspend fun receipts(accountId: String, state: String): List<BackupReceiptEntity>

    @Query("SELECT state, COUNT(*) AS count FROM backup_receipts WHERE accountId = :accountId GROUP BY state")
    fun observeStateCounts(accountId: String): Flow<List<StateCount>>

    /** Items backed up or handed over since [sinceMillis], whatever happened to them afterwards. */
    @Query(
        "SELECT COUNT(*) FROM backup_receipts WHERE accountId = :accountId AND backedUpAtMillis >= :sinceMillis " +
            "AND state != 'SKIPPED'",
    )
    fun observeBackedUpSince(accountId: String, sinceMillis: Long): Flow<Int>

    /** Received items (each content once) that the account has no receipt for yet. */
    @Query(
        "SELECT COUNT(DISTINCT contentId) FROM received_items AS item WHERE NOT EXISTS (SELECT 1 FROM " +
            "backup_receipts AS receipt WHERE receipt.accountId = :accountId AND receipt.contentId = item.contentId)",
    )
    fun observeWaiting(accountId: String): Flow<Int>
}
