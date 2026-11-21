/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database.di

import android.content.Context
import androidx.room.Room
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.core.database.BackupDao
import io.github.danyk20.mediasync.core.database.LeftOutItemDao
import io.github.danyk20.mediasync.core.database.MediaFingerprintDao
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PartialTransferDao
import io.github.danyk20.mediasync.core.database.PeerDao
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.database.SentItemDao

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(context: Context): MediaSyncDatabase =
        Room.databaseBuilder(context, MediaSyncDatabase::class.java, MediaSyncDatabase.NAME).build()

    @Provides
    fun providePeerDao(database: MediaSyncDatabase): PeerDao = database.peerDao()

    @Provides
    fun provideSentItemDao(database: MediaSyncDatabase): SentItemDao = database.sentItemDao()

    @Provides
    fun provideReceivedItemDao(database: MediaSyncDatabase): ReceivedItemDao = database.receivedItemDao()

    @Provides
    fun providePartialTransferDao(database: MediaSyncDatabase): PartialTransferDao = database.partialTransferDao()

    @Provides
    fun provideMediaFingerprintDao(database: MediaSyncDatabase): MediaFingerprintDao = database.mediaFingerprintDao()

    @Provides
    fun provideBackupDao(database: MediaSyncDatabase): BackupDao = database.backupDao()

    @Provides
    fun provideLeftOutItemDao(database: MediaSyncDatabase): LeftOutItemDao = database.leftOutItemDao()
}
