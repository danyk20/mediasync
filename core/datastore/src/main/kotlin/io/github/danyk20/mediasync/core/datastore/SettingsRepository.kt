/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import kotlinx.coroutines.flow.Flow

/** Device-level settings that survive app restarts. */
interface SettingsRepository {
    /** This device's role, or `null` until the user has chosen one. */
    val role: Flow<DeviceRole?>

    suspend fun setRole(role: DeviceRole)

    /** Which folders a sending phone sends; [SourceSelection.Default] until the user chooses. */
    val sourceSelection: Flow<SourceSelection>

    suspend fun setSourceSelection(selection: SourceSelection)

    /** A receiving phone's sync schedule, which its sending phones follow; [SyncSchedule.Default] until changed. */
    val syncSchedule: Flow<SyncSchedule>

    suspend fun setSyncSchedule(schedule: SyncSchedule)

    /** Which details a sending phone removes before sending (docs/PLAN.md §6.3); [PhotoDetails.Default] at first. */
    val photoDetails: Flow<PhotoDetails>

    suspend fun setPhotoDetails(details: PhotoDetails)

    /** A receiving phone's rendezvous Worker for internet sync (PLAN §8.4); `null` while it has none. */
    val rendezvousUrl: Flow<String?>

    suspend fun setRendezvousUrl(url: String?)

    /** A sending phone: internet sync (PLAN §8.4) also over mobile data, not only over Wi-Fi; off at first. */
    val internetOverMobileData: Flow<Boolean>

    suspend fun setInternetOverMobileData(allowed: Boolean)

    /**
     * A sending phone: videos also go through the relay; off at first, so over the relay they wait for Wi-Fi or a
     * direct connection (PLAN §17, relay saver).
     */
    val videosOverRelay: Flow<Boolean>

    suspend fun setVideosOverRelay(allowed: Boolean)

    /** The setup cards the user answered with *Not now*; they don't come back. */
    val dismissedPrompts: Flow<Set<SetupPrompt>>

    suspend fun dismissPrompt(prompt: SetupPrompt)
}

/** A home-screen card that asks the user to allow something background syncs need (docs/PLAN.md §10.3). */
enum class SetupPrompt {
    /** Android 13+: show sync progress. */
    NOTIFICATIONS,

    /** Android 12+: set battery use to *Unrestricted*. */
    BATTERY,
}
