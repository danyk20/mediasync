/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PeerEntity::class,
        SentItemEntity::class,
        ReceivedItemEntity::class,
        PartialTransferEntity::class,
        MediaFingerprintEntity::class,
        ConnectorAccountEntity::class,
        BackupReceiptEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // peers.schedule
        AutoMigration(from = 2, to = 3), // peers.turboKey
        AutoMigration(from = 3, to = 4), // connector_accounts, backup_receipts
        AutoMigration(from = 4, to = 5), // peers.pairSecret, peers.rendezvousUrl
    ],
)
abstract class MediaSyncDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao

    abstract fun sentItemDao(): SentItemDao

    abstract fun receivedItemDao(): ReceivedItemDao

    abstract fun partialTransferDao(): PartialTransferDao

    abstract fun mediaFingerprintDao(): MediaFingerprintDao

    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "mediasync.db"
    }
}
