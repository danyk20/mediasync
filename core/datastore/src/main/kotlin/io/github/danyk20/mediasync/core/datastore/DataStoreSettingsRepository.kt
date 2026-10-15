/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
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

    override val syncSchedule: Flow<SyncSchedule> = dataStore.data
        .map { preferences -> preferences[SyncScheduleKey]?.let(SyncSchedule::decode) ?: SyncSchedule.Default }
        .distinctUntilChanged()

    override suspend fun setSyncSchedule(schedule: SyncSchedule) {
        dataStore.edit { it[SyncScheduleKey] = schedule.encode() }
    }

    override val photoDetails: Flow<PhotoDetails> = dataStore.data
        .map { preferences ->
            preferences[PhotoDetailsKey]?.let { name -> PhotoDetails.entries.firstOrNull { it.name == name } }
                ?: PhotoDetails.Default
        }
        .distinctUntilChanged()

    override suspend fun setPhotoDetails(details: PhotoDetails) {
        dataStore.edit { it[PhotoDetailsKey] = details.name }
    }

    override val rendezvousUrl: Flow<String?> = dataStore.data.map { it[RendezvousUrlKey] }.distinctUntilChanged()

    override suspend fun setRendezvousUrl(url: String?) {
        dataStore.edit { if (url == null) it.remove(RendezvousUrlKey) else it[RendezvousUrlKey] = url }
    }

    override val internetOverMobileData: Flow<Boolean> =
        dataStore.data.map { it[InternetOverMobileDataKey] ?: false }.distinctUntilChanged()

    override suspend fun setInternetOverMobileData(allowed: Boolean) {
        dataStore.edit { it[InternetOverMobileDataKey] = allowed }
    }

    override val mobileDataCapBytes: Flow<Long?> =
        dataStore.data.map { it[MobileDataCapKey]?.takeIf { cap -> cap > 0 } }.distinctUntilChanged()

    override suspend fun setMobileDataCapBytes(bytes: Long?) {
        dataStore.edit { if (bytes == null) it.remove(MobileDataCapKey) else it[MobileDataCapKey] = bytes }
    }

    override val videosOverRelay: Flow<Boolean> =
        dataStore.data.map { it[VideosOverRelayKey] ?: false }.distinctUntilChanged()

    override suspend fun setVideosOverRelay(allowed: Boolean) {
        dataStore.edit { it[VideosOverRelayKey] = allowed }
    }

    override val dismissedPrompts: Flow<Set<SetupPrompt>> = dataStore.data
        .map { preferences ->
            preferences[DismissedPromptsKey].orEmpty().mapNotNullTo(mutableSetOf()) { name ->
                SetupPrompt.entries.firstOrNull { it.name == name }
            }
        }
        .distinctUntilChanged()

    override suspend fun dismissPrompt(prompt: SetupPrompt) {
        dataStore.edit { it[DismissedPromptsKey] = it[DismissedPromptsKey].orEmpty() + prompt.name }
    }

    internal companion object {
        val RoleKey = stringPreferencesKey("device_role")
        val SyncScheduleKey = stringPreferencesKey("sync_schedule")
        val SourcePresetKey = stringPreferencesKey("source_preset")
        val SourceFoldersKey = stringSetPreferencesKey("source_folders")
        val DismissedPromptsKey = stringSetPreferencesKey("dismissed_prompts")
        val PhotoDetailsKey = stringPreferencesKey("photo_details")
        val RendezvousUrlKey = stringPreferencesKey("rendezvous_url")
        val InternetOverMobileDataKey = booleanPreferencesKey("internet_over_mobile_data")
        val VideosOverRelayKey = booleanPreferencesKey("videos_over_relay")
        val MobileDataCapKey = longPreferencesKey("mobile_data_cap_bytes")

        /** Stored instead of a preset name when the user picked folders by hand. */
        private const val CHOSEN_FOLDERS = "CHOSEN_FOLDERS"

        /** Values written by a newer app version that this version doesn't know are treated as "not chosen". */
        private fun String.toDeviceRole(): DeviceRole? = DeviceRole.entries.firstOrNull { it.name == this }
    }
}
