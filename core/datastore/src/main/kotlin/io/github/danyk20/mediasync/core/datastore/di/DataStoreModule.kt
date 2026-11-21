/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.core.datastore.DataStoreSettingsRepository
import io.github.danyk20.mediasync.core.datastore.SettingsRepository

@ContributesTo(AppScope::class)
@BindingContainer
interface DataStoreModule {

    @Binds
    val DataStoreSettingsRepository.bindSettingsRepository: SettingsRepository

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun providePreferencesDataStore(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    }
}
