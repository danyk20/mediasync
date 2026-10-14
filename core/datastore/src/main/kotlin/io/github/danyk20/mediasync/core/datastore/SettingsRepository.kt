/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SourceSelection
import kotlinx.coroutines.flow.Flow

/** Device-level settings that survive app restarts. */
interface SettingsRepository {
    /** This device's role, or `null` until the user has chosen one. */
    val role: Flow<DeviceRole?>

    suspend fun setRole(role: DeviceRole)

    /** Which folders a sending phone sends; [SourceSelection.Default] until the user chooses. */
    val sourceSelection: Flow<SourceSelection>

    suspend fun setSourceSelection(selection: SourceSelection)
}
