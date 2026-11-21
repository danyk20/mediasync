/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path

/**
 * The settings store in the file [producePath] gives (`settings.preferences_pb` by convention): the same file format
 * and keys on every platform. The desktop app uses this; Android's graph builds the same store in `:app` with
 * `preferencesDataStoreFile("settings")`.
 */
fun createSettingsDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    producePath: () -> Path,
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = producePath)
