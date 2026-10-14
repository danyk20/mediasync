/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.testing

import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [SettingsRepository] for ViewModel and UI tests. */
class FakeSettingsRepository(initialRole: DeviceRole? = null) : SettingsRepository {
    private val roleState = MutableStateFlow(initialRole)

    override val role: StateFlow<DeviceRole?> = roleState

    override suspend fun setRole(role: DeviceRole) {
        roleState.value = role
    }
}
