/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A transfer in progress, as the home screen shows it. */
data class IncomingTransfer(
    val producerName: String,
    val itemsDone: Int,
    val itemsTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class ConsumerHomeUiState(
    val listening: Boolean = false,
    val transfer: IncomingTransfer? = null,
    val producers: List<String> = emptyList(),
    val receivedCount: Int = 0,
    val schedule: SyncSchedule = SyncSchedule.Default,
)

@HiltViewModel
class ConsumerHomeViewModel @Inject constructor(
    private val receiver: ConsumerReceiver,
    peers: PeerStore,
    received: RoomReceivedLedger,
    settings: SettingsRepository,
) : ViewModel() {

    init {
        // Receives while this screen is open; scheduled receive windows also run in the background.
        receiver.hold(HOLDER)
    }

    val uiState: StateFlow<ConsumerHomeUiState> = combine(
        receiver.state,
        peers.observeAll(),
        received.observeCount(),
        settings.syncSchedule,
    ) { state, allPeers, count, schedule ->
        val producers = allPeers.filter { it.role == DeviceRole.PRODUCER }
        val transfer = state.transfers.entries.firstOrNull()?.let { (id, progress) ->
            IncomingTransfer(
                producerName = producers.firstOrNull { it.id == id }?.name ?: "",
                itemsDone = progress.itemsDone,
                itemsTotal = progress.itemsTotal,
                bytesDone = progress.bytesDone,
                bytesTotal = progress.bytesTotal,
            )
        }
        ConsumerHomeUiState(state.listening, transfer, producers.map { it.name }, count, schedule)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ConsumerHomeUiState())

    override fun onCleared() = receiver.release(HOLDER)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val HOLDER = "home-screen"
    }
}
