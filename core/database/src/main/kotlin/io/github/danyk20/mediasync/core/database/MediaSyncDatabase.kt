/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PeerEntity::class,
        SentItemEntity::class,
        ReceivedItemEntity::class,
        PartialTransferEntity::class,
        MediaFingerprintEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MediaSyncDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao

    abstract fun sentItemDao(): SentItemDao

    abstract fun receivedItemDao(): ReceivedItemDao

    abstract fun partialTransferDao(): PartialTransferDao

    abstract fun mediaFingerprintDao(): MediaFingerprintDao

    companion object {
        const val NAME = "mediasync.db"
    }
}
