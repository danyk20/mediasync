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
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.Pending
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.SyncHistory
import io.github.danyk20.mediasync.sync.android.SyncProblem
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    /** Photos and videos the *Photo details* setting holds back: their details can't be removed. */
    val withheldCount: Int = 0,
    val photoDetails: PhotoDetails = PhotoDetails.Default,
    val sources: SourceSelection = SourceSelection.Default,
    /** The receiving phone's schedule, which this phone follows; `null` until paired. */
    val schedule: SyncSchedule? = null,
    /** When the next scheduled sync is due, as of [now]; `null` without a timed schedule. */
    val nextSyncAt: ZonedDateTime? = null,
    val now: ZonedDateTime? = null,
    /** When a sync last sent everything it could. */
    val lastSyncMillis: Long? = null,
    /** Photos and videos the receiving phone had no space for in the last sync. */
    val waitingForSpace: Int = 0,
    /** A scheduled sync that should have happened by now but didn't. */
    val missedSync: MissedSync? = null,
    /** Android 13+: ask to show sync progress. */
    val askNotifications: Boolean = false,
    /** Android 12+: ask for *Unrestricted* battery use, which syncs at night need. */
    val askBattery: Boolean = false,
)

/**
 * A scheduled sync that didn't happen (docs/PLAN.md §10.3), and what's known about why: the [problem] its last
 * try ran into, or that Android restricts the app's battery use.
 */
data class MissedSync(val due: ZonedDateTime, val problem: SyncProblem?, val batteryRestricted: Boolean)

/** What the home screen shows besides the sync itself. */
private data class Extras(
    val access: DeviceAccess,
    val record: SyncRecord,
    val dismissed: Set<SetupPrompt>,
    val details: PhotoDetails,
)

/** What the phone allows MediaSync, as of [checkedAtMillis]. */
private data class DeviceAccess(
    val library: Boolean,
    val location: Boolean,
    val canNotify: Boolean,
    val batteryRestricted: Boolean,
    val checkedAtMillis: Long,
)

@HiltViewModel
class ProducerHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ProducerSync,
    private val scheduler: SyncScheduler,
    peers: PeerStore,
    library: MediaLibrary,
    private val settings: SettingsRepository,
    history: SyncHistory,
    private val setup: BackgroundSetup,
    private val clock: Clock,
) : ViewModel() {

    private val access = MutableStateFlow(checkAccess())
    private val pending = MutableStateFlow<Pending?>(null)

    val uiState: StateFlow<ProducerHomeUiState> = combine(
        peers.observeConsumer(),
        sync.state,
        pending,
        settings.sourceSelection,
        combine(access, history.record, settings.dismissedPrompts, settings.photoDetails, ::Extras),
    ) { consumer, state, count, sources, extras ->
        val (access, record, dismissed) = extras
        val schedule = consumer?.let { it.schedule ?: SyncSchedule.Default }
        val now = ZonedDateTime.now(clock)
        ProducerHomeUiState(
            consumerName = consumer?.name,
            canReadLibrary = access.library,
            canReadLocation = access.location,
            sync = state,
            pendingCount = count?.toSend,
            withheldCount = count?.withheld ?: 0,
            photoDetails = extras.details,
            sources = sources,
            schedule = schedule,
            nextSyncAt = schedule?.nextDue(now),
            now = now,
            lastSyncMillis = record.lastSyncMillis,
            waitingForSpace = record.waitingForSpace,
            missedSync = schedule?.let(scheduler::missedSync)
                ?.let { MissedSync(it, record.problem, access.batteryRestricted) },
            askNotifications = !access.canNotify && SetupPrompt.NOTIFICATIONS !in dismissed,
            askBattery = access.batteryRestricted &&
                schedule != SyncSchedule.Manual &&
                SetupPrompt.BATTERY !in dismissed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProducerHomeUiState())

    init {
        // Recount whenever a sync ends, the receiving phone or the chosen folders change, or photos are added,
        // edited or deleted. A newer reason cancels a count still running.
        @OptIn(FlowPreview::class)
        val reasons = merge(
            uiState.distinctUntilChangedBy {
                listOf(it.consumerName, it.canReadLibrary, it.sync is ProducerState.Idle, it.sources, it.photoDetails)
            }
                .map { },
            library.changes().debounce(LIBRARY_SETTLE_MILLIS),
        )
        viewModelScope.launch { reasons.collectLatest { countPending() } }
    }

    /** Sends in the background, so it carries on when the user leaves the app. */
    fun syncNow() = scheduler.syncNow()

    fun stop() = sync.cancel()

    /** Call when the screen is shown again, e.g. back from settings, and after the user answered a prompt. */
    fun onPermissionsChanged() {
        access.value = checkAccess()
    }

    /** The user answered a setup card with *Not now*, or declined what it asked for. */
    fun dismiss(prompt: SetupPrompt) {
        viewModelScope.launch { settings.dismissPrompt(prompt) }
    }

    private suspend fun countPending() {
        val state = uiState.value
        if (state.consumerName == null || !state.canReadLibrary || state.sync !is ProducerState.Idle) return
        pending.value = sync.pendingCount()
    }

    private fun checkAccess() = DeviceAccess(
        library = MediaPermissions.canReadLibrary(context),
        location = MediaPermissions.canReadLocation(context),
        canNotify = setup.canNotify(),
        batteryRestricted = setup.batteryRestricted(),
        checkedAtMillis = clock.millis(), // Also brings the times shown up to date.
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
