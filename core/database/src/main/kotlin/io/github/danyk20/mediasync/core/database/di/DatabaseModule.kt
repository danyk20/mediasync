/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.danyk20.mediasync.core.database.MediaFingerprintDao
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PartialTransferDao
import io.github.danyk20.mediasync.core.database.PeerDao
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.database.SentItemDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MediaSyncDatabase =
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
}
