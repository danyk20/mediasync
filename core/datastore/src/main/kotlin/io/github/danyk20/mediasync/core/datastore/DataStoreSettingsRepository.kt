/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danyk20.mediasync.core.model.DeviceRole
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class DataStoreSettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) :
    SettingsRepository {

    override val role: Flow<DeviceRole?> = dataStore.data
        .map { preferences -> preferences[RoleKey]?.toDeviceRole() }
        .distinctUntilChanged()

    override suspend fun setRole(role: DeviceRole) {
        dataStore.edit { it[RoleKey] = role.name }
    }

    internal companion object {
        val RoleKey = stringPreferencesKey("device_role")

        /** Values written by a newer app version that this version doesn't know are treated as "not chosen". */
        private fun String.toDeviceRole(): DeviceRole? = DeviceRole.entries.firstOrNull { it.name == this }
    }
}
