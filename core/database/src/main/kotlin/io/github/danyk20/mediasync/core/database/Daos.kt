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
