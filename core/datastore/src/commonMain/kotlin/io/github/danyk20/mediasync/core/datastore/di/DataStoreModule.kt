/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import io.github.danyk20.mediasync.core.datastore.DataStoreSettingsRepository
import io.github.danyk20.mediasync.core.datastore.SettingsRepository

/** The `DataStore<Preferences>` it needs is provided per platform (on Android, by `:app`). */
@ContributesTo(AppScope::class)
@BindingContainer
interface DataStoreModule {

    @Binds
    val DataStoreSettingsRepository.bindSettingsRepository: SettingsRepository
}
