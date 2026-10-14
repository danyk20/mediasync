/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
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

    override val sourceSelection: Flow<SourceSelection> = dataStore.data
        .map { preferences ->
            when (val preset = preferences[SourcePresetKey]) {
                null -> SourceSelection.Default

                CHOSEN_FOLDERS -> SourceSelection.of(preferences[SourceFoldersKey].orEmpty())

                else -> SourcePreset.entries.firstOrNull { it.name == preset }?.let(::SourceSelection)
                    ?: SourceSelection.Default
            }
        }
        .distinctUntilChanged()

    override suspend fun setSourceSelection(selection: SourceSelection) {
        dataStore.edit {
            it[SourcePresetKey] = selection.preset?.name ?: CHOSEN_FOLDERS
            it[SourceFoldersKey] = if (selection.preset == null) selection.folders else emptySet()
        }
    }

    internal companion object {
        val RoleKey = stringPreferencesKey("device_role")
        val SourcePresetKey = stringPreferencesKey("source_preset")
        val SourceFoldersKey = stringSetPreferencesKey("source_folders")

        /** Stored instead of a preset name when the user picked folders by hand. */
        private const val CHOSEN_FOLDERS = "CHOSEN_FOLDERS"

        /** Values written by a newer app version that this version doesn't know are treated as "not chosen". */
        private fun String.toDeviceRole(): DeviceRole? = DeviceRole.entries.firstOrNull { it.name == this }
    }
}
