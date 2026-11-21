/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase

/** What the shared storage modules need from Android: the settings file and the database, where they always were. */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidStorageModule {

    @Provides
    @SingleIn(AppScope::class)
    fun providePreferencesDataStore(context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(context: Context): MediaSyncDatabase =
        Room.databaseBuilder(context, MediaSyncDatabase::class.java, MediaSyncDatabase.NAME).build()
}
