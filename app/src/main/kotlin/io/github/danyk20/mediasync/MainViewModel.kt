/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface MainUiState {
    /** Settings are still loading; the window background is shown meanwhile. */
    data object Loading : MainUiState

    /** @property role this device's role, or `null` if onboarding hasn't been completed. */
    data class Ready(val role: DeviceRole?) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(settingsRepository: SettingsRepository) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsRepository.role
        .map<DeviceRole?, MainUiState> { MainUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = MainUiState.Loading,
        )

    private val _openedLink = MutableStateFlow<String?>(null)

    /** A pairing link opened from outside the app, waiting until a producer screen can show it. */
    val openedLink: StateFlow<String?> = _openedLink.asStateFlow()

    fun onLinkOpened(link: String) {
        _openedLink.value = link
    }

    fun linkHandled() {
        _openedLink.value = null
    }

    private companion object {
        /** Keeps the flow alive across configuration changes. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
