/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RoleChoiceViewModel @Inject constructor(private val settingsRepository: SettingsRepository) : ViewModel() {

    /** Stores the role. The app observes it and moves on to the matching home screen. */
    fun chooseRole(role: DeviceRole) {
        viewModelScope.launch { settingsRepository.setRole(role) }
    }
}
