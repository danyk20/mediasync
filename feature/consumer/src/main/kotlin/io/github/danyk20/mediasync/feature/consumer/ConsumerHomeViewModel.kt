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
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Too little free space to receive more (docs/PLAN.md §2.2). */
    val storageFull: Boolean = false,
    /** Photos and videos the last sync offered that didn't fit. */
    val waitingForSpace: Int = 0,
    /** Android 13+: ask to show sync progress. */
    val askNotifications: Boolean = false,
    /** Android 12+: ask for *Unrestricted* battery use, which receive windows at night need. */
    val askBattery: Boolean = false,
    /** A direct link for Turbo transfer (docs/PLAN.md §8.3) is open. */
    val directLinkOpen: Boolean = false,
    /** A sending phone asked for Turbo transfer, which needs the user's permission (or location on) here. */
    val askTurbo: Boolean = false,
    val turbo: TurboAvailability = TurboAvailability.UNSUPPORTED,
    /** *Turbo receive* is on: waiting for a sending phone that isn't on this phone's Wi-Fi. */
    val turboReceiving: Boolean = false,
)

/** What the phone allows MediaSync, and its free space. */
private data class DeviceAccess(
    val canNotify: Boolean,
    val batteryRestricted: Boolean,
    val freeBytes: Long,
    val turbo: TurboAvailability,
)

@HiltViewModel
class ConsumerHomeViewModel @Inject constructor(
    private val receiver: ConsumerReceiver,
    peers: PeerStore,
    received: RoomReceivedLedger,
    private val settings: SettingsRepository,
    private val setup: BackgroundSetup,
) : ViewModel() {

    private val access = MutableStateFlow(checkAccess())

    init {
        // Receives while this screen is open; scheduled receive windows also run in the background.
        receiver.hold(HOLDER)
        // A sync that ran into a full storage: check the free space again.
        viewModelScope.launch { receiver.state.map { it.waitingForSpace }.distinctUntilChanged().collect { refresh() } }
    }

    val uiState: StateFlow<ConsumerHomeUiState> = combine(
        receiver.state,
        peers.observeAll(),
        received.observeCount(),
        settings.syncSchedule,
        combine(access, settings.dismissedPrompts, ::Pair),
    ) { state, allPeers, count, schedule, (access, dismissed) ->
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
        ConsumerHomeUiState(
            listening = state.listening,
            transfer = transfer,
            producers = producers.map { it.name },
            receivedCount = count,
            schedule = schedule,
            storageFull = access.freeBytes < LOW_SPACE_BYTES,
            waitingForSpace = state.waitingForSpace,
            askNotifications = !access.canNotify && SetupPrompt.NOTIFICATIONS !in dismissed,
            askBattery = access.batteryRestricted &&
                schedule != SyncSchedule.Manual &&
                SetupPrompt.BATTERY !in dismissed,
            directLinkOpen = state.directLinkOpen,
            askTurbo = state.turboNeedsPermission && access.turbo != TurboAvailability.AVAILABLE,
            turbo = access.turbo,
            turboReceiving = state.turboReceiving,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ConsumerHomeUiState())

    /** Call when the screen is shown again, e.g. back from settings, and after the user answered a prompt. */
    fun refresh() {
        access.value = checkAccess()
    }

    /** The user answered a setup card with *Not now*, or declined what it asked for. */
    fun dismiss(prompt: SetupPrompt) {
        viewModelScope.launch { settings.dismissPrompt(prompt) }
    }

    /**
     * *Turbo receive* (docs/PLAN.md §8.3), for a sending phone that isn't on this Wi-Fi. Setting up the link takes a
     * few seconds, off the main thread.
     */
    fun startTurboReceive() {
        refresh()
        if (access.value.turbo != TurboAvailability.AVAILABLE) return
        viewModelScope.launch(Dispatchers.IO) { receiver.turbo.startReceive() }
    }

    fun stopTurboReceive() = receiver.turbo.stopReceive()

    /** The answer to Turbo's permission request, or *Not now*: the card goes either way. */
    fun onTurboAnswered() {
        receiver.turbo.permissionHandled()
        refresh()
    }

    override fun onCleared() = receiver.release(HOLDER)

    private fun checkAccess() = DeviceAccess(
        setup.canNotify(),
        setup.batteryRestricted(),
        receiver.freeBytes(),
        receiver.turbo.availability(),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val HOLDER = "home-screen"

        /** Above the 2 GB the storage guard keeps free: less than this, and hardly anything fits. */
        const val LOW_SPACE_BYTES = 100L * 1024 * 1024
    }
}
