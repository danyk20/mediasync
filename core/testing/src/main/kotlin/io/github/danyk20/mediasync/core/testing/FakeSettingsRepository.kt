/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.testing

import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [SettingsRepository] for ViewModel and UI tests. */
class FakeSettingsRepository(
    initialRole: DeviceRole? = null,
    initialSourceSelection: SourceSelection = SourceSelection.Default,
    initialSyncSchedule: SyncSchedule = SyncSchedule.Default,
) : SettingsRepository {
    private val roleState = MutableStateFlow(initialRole)
    private val sourceSelectionState = MutableStateFlow(initialSourceSelection)

    override val role: StateFlow<DeviceRole?> = roleState

    override suspend fun setRole(role: DeviceRole) {
        roleState.value = role
    }

    override val sourceSelection: StateFlow<SourceSelection> = sourceSelectionState

    override suspend fun setSourceSelection(selection: SourceSelection) {
        sourceSelectionState.value = selection
    }

    private val syncScheduleState = MutableStateFlow(initialSyncSchedule)

    override val syncSchedule: StateFlow<SyncSchedule> = syncScheduleState

    override suspend fun setSyncSchedule(schedule: SyncSchedule) {
        syncScheduleState.value = schedule
    }

    private val photoDetailsState = MutableStateFlow(PhotoDetails.Default)

    override val photoDetails: StateFlow<PhotoDetails> = photoDetailsState

    override suspend fun setPhotoDetails(details: PhotoDetails) {
        photoDetailsState.value = details
    }

    private val rendezvousUrlState = MutableStateFlow<String?>(null)

    override val rendezvousUrl: StateFlow<String?> = rendezvousUrlState

    override suspend fun setRendezvousUrl(url: String?) {
        rendezvousUrlState.value = url
    }

    private val internetOverMobileDataState = MutableStateFlow(false)

    override val internetOverMobileData: StateFlow<Boolean> = internetOverMobileDataState

    override suspend fun setInternetOverMobileData(allowed: Boolean) {
        internetOverMobileDataState.value = allowed
    }

    private val mobileDataCapState = MutableStateFlow<Long?>(null)

    override val mobileDataCapBytes: StateFlow<Long?> = mobileDataCapState

    override suspend fun setMobileDataCapBytes(bytes: Long?) {
        mobileDataCapState.value = bytes
    }

    private val videosOverRelayState = MutableStateFlow(false)

    override val videosOverRelay: StateFlow<Boolean> = videosOverRelayState

    override suspend fun setVideosOverRelay(allowed: Boolean) {
        videosOverRelayState.value = allowed
    }

    private val dismissedPromptsState = MutableStateFlow(emptySet<SetupPrompt>())

    override val dismissedPrompts: StateFlow<Set<SetupPrompt>> = dismissedPromptsState

    override suspend fun dismissPrompt(prompt: SetupPrompt) {
        dismissedPromptsState.value += prompt
    }
}
