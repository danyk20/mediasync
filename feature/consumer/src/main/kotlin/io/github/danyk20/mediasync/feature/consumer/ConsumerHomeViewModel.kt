/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Internet sync (docs/PLAN.md §8.4) is set up: this phone has a rendezvous Worker. */
    val internetSync: Boolean = false,
    /** This month's relay usage (docs/PLAN.md §8.5), for the one-line meter; `null` until known. */
    val relay: RelayUsage? = null,
    /** Cloud backup (docs/PLAN.md §9.3.1); `null` in a build without any. */
    val backup: BackupLine? = null,
)

/** Cloud backup, as the home screen's row shows it. */
sealed interface BackupLine {
    /** Not set up: received photos stay on this phone only. */
    data object Off : BackupLine

    /** [app] on this phone backs up what's received; [today] were handed to it since midnight. */
    data class On(val app: String, val today: Int) : BackupLine
}

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
    backup: CloudBackup,
) : ViewModel() {

    private val access = MutableStateFlow(checkAccess())
    private val relay = MutableStateFlow<RelayUsage?>(null)

    init {
        // Receives while this screen is open; scheduled receive windows also run in the background.
        receiver.hold(HOLDER)
        // A sync that ran into a full storage: check the free space again.
        viewModelScope.launch { receiver.state.map { it.waitingForSpace }.distinctUntilChanged().collect { refresh() } }
        // The relay meter, while internet sync is on: read again now and then, as relayed transfers run.
        viewModelScope.launch {
            settings.rendezvousUrl.collectLatest { worker ->
                relay.value = null
                while (worker != null) {
                    relay.value = withContext(Dispatchers.IO) { receiver.relayUsage() }
                    delay(RELAY_REFRESH_MILLIS)
                }
            }
        }
    }

    val uiState: StateFlow<ConsumerHomeUiState> = combine(
        receiver.state,
        peers.observeAll(),
        received.observeCount(),
        combine(settings.syncSchedule, settings.rendezvousUrl, relay, ::Triple),
        combine(access, settings.dismissedPrompts, backupLine(backup), ::Triple),
    ) { state, allPeers, count, (schedule, worker, relay), (access, dismissed, backupLine) ->
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
            internetSync = worker != null,
            relay = relay?.takeIf { worker != null },
            backup = backupLine,
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

    /** The first account backing up: v1.0 has one, the Google Photos app (docs/PLAN.md §9.3.1). */
    private fun backupLine(backup: CloudBackup): Flow<BackupLine?> = backup.overviews().map { overviews ->
        val first = overviews.firstOrNull()
        when {
            first != null -> BackupLine.On(first.account.displayName, first.today)
            backup.deviceApps.isEmpty() -> null
            else -> BackupLine.Off
        }
    }

    private fun checkAccess() = DeviceAccess(
        setup.canNotify(),
        setup.batteryRestricted(),
        receiver.freeBytes(),
        receiver.turbo.availability(),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val HOLDER = "home-screen"
        const val RELAY_REFRESH_MILLIS = 5 * 60_000L

        /** Above the 2 GB the storage guard keeps free: less than this, and hardly anything fits. */
        const val LOW_SPACE_BYTES = 100L * 1024 * 1024
    }
}
