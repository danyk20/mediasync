/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProducerHomeUiState(
    /** `null` until a receiving phone is paired. */
    val consumerName: String? = null,
    val canReadLibrary: Boolean = false,
    val canReadLocation: Boolean = false,
    val sync: ProducerState = ProducerState.Idle(),
    /** Photos and videos not yet sent; `null` while counting. */
    val pendingCount: Int? = null,
)

@HiltViewModel
class ProducerHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ProducerSync,
    peers: PeerStore,
) : ViewModel() {

    private val permissions = MutableStateFlow(currentPermissions())
    private val pending = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<ProducerHomeUiState> = combine(
        peers.observeAll().map { all -> all.firstOrNull { it.role == DeviceRole.CONSUMER }?.name },
        permissions,
        sync.state,
        pending,
    ) { consumer, (library, location), state, count ->
        ProducerHomeUiState(consumer, library, location, state, count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProducerHomeUiState())

    init {
        // Recount whenever a sync ends or the receiving phone changes.
        viewModelScope.launch {
            uiState.distinctUntilChangedBy { Triple(it.consumerName, it.canReadLibrary, it.sync is ProducerState.Idle) }
                .collect { refreshPendingCount() }
        }
    }

    fun syncNow() = sync.syncNow()

    fun stop() = sync.cancel()

    /** Call after the user answered a permission prompt or came back from settings. */
    fun onPermissionsChanged() {
        permissions.value = currentPermissions()
    }

    private fun refreshPendingCount() {
        val state = uiState.value
        if (state.consumerName == null || !state.canReadLibrary || state.sync !is ProducerState.Idle) return
        viewModelScope.launch { pending.value = sync.pendingCount() }
    }

    private fun currentPermissions() =
        MediaPermissions.canReadLibrary(context) to MediaPermissions.canReadLocation(context)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
