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
import io.github.danyk20.mediasync.core.designsystem.component.SpeedPoint
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendRange
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.MobileDataUsage
import io.github.danyk20.mediasync.sync.android.Pending
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.SyncHistory
import io.github.danyk20.mediasync.sync.android.SyncProblem
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
    /** Their size. */
    val pendingBytes: Long = 0,
    /** Whether Turbo transfer (docs/PLAN.md §8.3) can be used, as far as this phone goes. */
    val turbo: TurboAvailability = TurboAvailability.UNSUPPORTED,
    /** Battery below 30 % and not charging: Turbo uses more power. */
    val turboBatteryLow: Boolean = false,
    /** The user declined Turbo's permission: only the app's settings can grant it now. */
    val turboDeclined: Boolean = false,
    /** Photos and videos the *Photo details* setting holds back: their details can't be removed. */
    val withheldCount: Int = 0,
    val photoDetails: PhotoDetails = PhotoDetails.Default,
    val sendingOrder: SendingOrder = SendingOrder.Default,
    val sources: SourceSelection = SourceSelection.Default,
    /** The days whose photos and videos are sent. */
    val sendRange: SendRange = SendRange.Everything,
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
    /** The receiving phone has a rendezvous Worker: this phone can sync with it over the internet (PLAN §8.4). */
    val internetSync: Boolean = false,
    /** Internet sync may also use mobile data. */
    val internetOverMobileData: Boolean = false,
    /** Videos also go through the relay; otherwise they wait for Wi-Fi or a direct connection (relay saver). */
    val videosOverRelay: Boolean = false,
    /** How much internet sync may send over mobile data a month; `null`: no limit. */
    val mobileDataCap: Long? = null,
    /** What it sent over mobile data this month. */
    val mobileDataSent: Long = 0,
)

/** Which folders this phone sends, and from which days. */
private data class Sources(val selection: SourceSelection, val range: SendRange)

/** How this phone uses internet sync, as the user set it. */
private data class InternetSettings(
    val sources: Sources,
    val mobileData: Boolean,
    val videosOverRelay: Boolean,
    val mobileDataCap: Long?,
    val mobileDataSent: Long,
)

/**
 * A scheduled sync that didn't happen (docs/PLAN.md §10.3), and what's known about why: the [problem] its last
 * try ran into, or that Android restricts the app's battery use.
 */
data class MissedSync(val due: ZonedDateTime, val problem: SyncProblem?, val batteryRestricted: Boolean)

/** What the user chose about how this phone sends. */
private data class SendingChoices(val details: PhotoDetails, val order: SendingOrder)

/** What the home screen shows besides the sync itself. */
private data class Extras(
    val access: DeviceAccess,
    val record: SyncRecord,
    val dismissed: Set<SetupPrompt>,
    val choices: SendingChoices,
    val turboDeclined: Boolean,
)

/** What the phone allows MediaSync, as of [checkedAtMillis]. */
private data class DeviceAccess(
    val library: Boolean,
    val location: Boolean,
    val canNotify: Boolean,
    val batteryRestricted: Boolean,
    val turbo: TurboAvailability,
    val turboBatteryLow: Boolean,
    val checkedAtMillis: Long,
)

@HiltViewModel
@Suppress("LongParameterList") // Injected collaborators, one each.
class ProducerHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ProducerSync,
    private val scheduler: SyncScheduler,
    private val peers: PeerStore,
    library: MediaLibrary,
    private val settings: SettingsRepository,
    history: SyncHistory,
    private val setup: BackgroundSetup,
    private val clock: Clock,
    mobileDataUsage: MobileDataUsage,
) : ViewModel() {

    private val access = MutableStateFlow(checkAccess())
    private val pending = MutableStateFlow<Pending?>(null)
    private val turboDeclined = MutableStateFlow(false)

    val uiState: StateFlow<ProducerHomeUiState> = combine(
        peers.observeConsumer(),
        sync.state,
        pending,
        combine(
            combine(settings.sourceSelection, settings.sendRange, ::Sources),
            settings.internetOverMobileData,
            settings.videosOverRelay,
            settings.mobileDataCapBytes,
            mobileDataUsage.sentThisMonth,
            ::InternetSettings,
        ),
        combine(
            access,
            history.record,
            settings.dismissedPrompts,
            combine(settings.photoDetails, settings.sendingOrder, ::SendingChoices),
            turboDeclined,
            ::Extras,
        ),
    ) { consumer, state, count, internet, extras ->
        val sources = internet.sources
        val (access, record, dismissed) = extras
        val schedule = consumer?.let { it.schedule ?: SyncSchedule.Default }
        val now = ZonedDateTime.now(clock)
        ProducerHomeUiState(
            consumerName = consumer?.name,
            canReadLibrary = access.library,
            canReadLocation = access.location,
            sync = state,
            pendingCount = count?.toSend,
            pendingBytes = count?.bytes ?: 0,
            turbo = access.turbo,
            turboBatteryLow = access.turboBatteryLow,
            turboDeclined = extras.turboDeclined && access.turbo == TurboAvailability.NOT_ALLOWED,
            withheldCount = count?.withheld ?: 0,
            photoDetails = extras.choices.details,
            sendingOrder = extras.choices.order,
            sources = sources.selection,
            sendRange = sources.range,
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
            internetSync = consumer?.rendezvousUrl != null,
            internetOverMobileData = internet.mobileData,
            videosOverRelay = internet.videosOverRelay,
            mobileDataCap = internet.mobileDataCap,
            mobileDataSent = internet.mobileDataSent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProducerHomeUiState())

    /** The speed every second of this sync, or of the last one, for its chart. */
    val speedHistory: StateFlow<List<SpeedPoint>> = sync.speedHistory
        .map { samples -> samples.map { SpeedPoint(it.atMillis, it.bytesPerSecond) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    init {
        // Recount whenever a sync ends, the receiving phone, folders or day change, or photos are added,
        // edited or deleted. A newer reason cancels a count still running.
        @OptIn(FlowPreview::class)
        val reasons = merge(
            uiState.distinctUntilChangedBy {
                listOf(
                    it.consumerName,
                    it.canReadLibrary,
                    it.sync is ProducerState.Idle,
                    it.sources,
                    it.sendRange,
                    it.photoDetails,
                )
            }
                .map { },
            library.changes().debounce(LIBRARY_SETTLE_MILLIS),
        )
        viewModelScope.launch { reasons.collectLatest { countPending() } }
    }

    /** Sends in the background, so it carries on when the user leaves the app. */
    fun syncNow() = scheduler.syncNow()

    fun stop() = sync.cancel()

    /**
     * Forgets the receiving phone, stopping a sync to it first. This phone can then pair with another one, or with
     * the same one again; what was sent stays there.
     */
    fun unpair() {
        sync.cancel()
        viewModelScope.launch(Dispatchers.IO) { peers.consumer()?.let { peers.forget(it.id) } }
    }

    fun setInternetOverMobileData(allowed: Boolean) {
        viewModelScope.launch { settings.setInternetOverMobileData(allowed) }
    }

    fun setMobileDataCap(bytes: Long?) {
        viewModelScope.launch { settings.setMobileDataCapBytes(bytes) }
    }

    fun setVideosOverRelay(allowed: Boolean) {
        viewModelScope.launch { settings.setVideosOverRelay(allowed) }
    }

    /** Sends over a direct link (docs/PLAN.md §8.3), in the background like [syncNow]. */
    fun startTurbo() {
        access.value = checkAccess()
        if (access.value.turbo == TurboAvailability.AVAILABLE) scheduler.syncNow(turbo = true)
    }

    /** The answer to Turbo's permission request; declined, only the app's settings can grant it after that. */
    fun onTurboPermission(granted: Boolean) {
        turboDeclined.value = !granted
        if (granted) startTurbo()
    }

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
        turbo = sync.turboAvailability(),
        turboBatteryLow = sync.turboBatteryLow(),
        checkedAtMillis = clock.millis(), // Also brings the times shown up to date.
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
