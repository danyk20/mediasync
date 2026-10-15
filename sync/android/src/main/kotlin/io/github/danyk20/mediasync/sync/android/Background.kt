/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SyncSchedule
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The receiving phone's schedule from its settings, kept current for the sessions that tell it. */
@Singleton
class SettingsScheduleSource @Inject constructor(
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : ScheduleSource {
    private val schedule = settings.syncSchedule.stateIn(scope, SharingStarted.Eagerly, SyncSchedule.Default)

    override fun current(): SyncSchedule = schedule.value
}

/** The sending phone's *Photo details* from its settings, kept current for the sessions that apply them. */
@Singleton
class SettingsPhotoDetailsSource @Inject constructor(
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : PhotoDetailsSource {
    private val details = settings.photoDetails.stateIn(scope, SharingStarted.Eagerly, PhotoDetails.Default)

    override fun current(): PhotoDetails = details.value
}

/** The receiving phone's rendezvous Worker from its settings, told to paired phones in every session (PLAN §8.4). */
@Singleton
class SettingsRendezvousSource @Inject constructor(
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : RendezvousSource {
    private val url = settings.rendezvousUrl.stateIn(scope, SharingStarted.Eagerly, null)

    override fun current(): String? = url.value
}

/** Whether the sending phone's internet sync may use mobile data, from its settings. */
@Singleton
class SettingsMobileDataSource @Inject constructor(
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : MobileDataSource {
    private val allowed = settings.internetOverMobileData.stateIn(scope, SharingStarted.Eagerly, false)
    private val cap = settings.mobileDataCapBytes.stateIn(scope, SharingStarted.Eagerly, null)

    override fun allowed(): Boolean = allowed.value

    override fun capBytes(): Long? = cap.value
}

/** Whether the sending phone sends videos through the relay too, from its settings. */
@Singleton
class SettingsRelayVideosSource @Inject constructor(
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : RelayVideosSource {
    private val allowed = settings.videosOverRelay.stateIn(scope, SharingStarted.Eagerly, false)

    override fun allowed(): Boolean = allowed.value
}

/** The settings the sync runtime follows as they change. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SettingsSourcesModule {
    @Binds
    abstract fun bindScheduleSource(impl: SettingsScheduleSource): ScheduleSource

    @Binds
    abstract fun bindPhotoDetailsSource(impl: SettingsPhotoDetailsSource): PhotoDetailsSource

    @Binds
    abstract fun bindRendezvousSource(impl: SettingsRendezvousSource): RendezvousSource

    @Binds
    abstract fun bindMobileDataSource(impl: SettingsMobileDataSource): MobileDataSource

    @Binds
    abstract fun bindRelayVideosSource(impl: SettingsRelayVideosSource): RelayVideosSource
}

/** What runs in the background for one role, and when. `null` schedule: nothing runs. */
internal data class BackgroundPlan(val role: DeviceRole, val schedule: SyncSchedule)

/**
 * Remembers the latest due time each role has handled. A due time missed while the phone wasn't charging
 * therefore still runs at the next charge, even if the app is opened (and everything rescheduled) in between.
 */
@Singleton
class ScheduleState @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("sync_schedule_state", Context.MODE_PRIVATE)

    fun lastHandledDue(role: DeviceRole): Long? = preferences.getLong(key(role), NONE).takeIf { it != NONE }

    /** Only ever moves forward. */
    fun markHandled(role: DeviceRole, dueMillis: Long) {
        if (dueMillis > (lastHandledDue(role) ?: Long.MIN_VALUE)) preferences.edit { putLong(key(role), dueMillis) }
    }

    private fun key(role: DeviceRole) = "last_handled_due_${role.name}"

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * Keeps one background job per role in WorkManager, following the sync schedule (docs/PLAN.md §10.1, §10.2):
 *
 * - A sending phone syncs at each due time while charging. One that isn't charging then syncs at its next
 *   charge. Starts are spread over a few minutes so phones don't all connect at once.
 * - A receiving phone opens a receive window a few minutes before each due time while charging.
 * - *Whenever charging*: a sending phone syncs when it's charging and has new photos; a receiving phone
 *   listens while it's charging. *Manual*: nothing runs in the background.
 *
 * A finished run queues the next one behind itself, so changing the schedule never cancels a transfer.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
    private val peers: PeerStore,
    private val state: ScheduleState,
    private val history: SyncHistory,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Follows the role and the schedule while the app runs. Call once, at start-up. */
    fun start() {
        scope.launch { plans().distinctUntilChanged().collect { apply(it) } }
    }

    /**
     * Receiving outside a receive window, with MediaSync open on screen or during *Turbo receive*, runs as foreground
     * work too, so it carries on when the screen goes off or the user switches apps (docs/PLAN.md §7, §10.2). Call
     * once, at start-up, with the receiver's state.
     */
    fun followReceiving(states: Flow<ReceiverState>) {
        scope.launch {
            states.map { it.sessions > 0 || it.directLinkOpen }
                .distinctUntilChanged()
                .collect { busy -> if (busy) receiveNow() }
        }
    }

    private fun receiveNow() {
        val request = OneTimeWorkRequest.Builder(ReceiveNowWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(RECEIVE_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Sends now as a foreground job that carries on when the user leaves the app (docs/PLAN.md §10.2). With
     * [turbo], over a direct link (§8.3).
     */
    fun syncNow(turbo: Boolean = false) {
        val request = OneTimeWorkRequest.Builder(SyncNowWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(Data.Builder().putBoolean(SyncNowWorker.TURBO, turbo).build())
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.KEEP, request)
        Diagnostics.log(Area.SCHEDULE, if (turbo) "Turbo transfer requested" else "Sync now requested")
    }

    /**
     * The sending phone's latest due time that should have synced by now but didn't (docs/PLAN.md §10.3), or
     * `null`. A due time counts as missed an hour after it, as background jobs may start up to ~31 minutes late,
     * and no longer once any sync, e.g. *Sync now*, got through after it.
     */
    fun missedSync(schedule: SyncSchedule): ZonedDateTime? {
        val now = ZonedDateTime.now(clock)
        val previous = schedule.previousDue(now) ?: return null
        val handled = state.lastHandledDue(DeviceRole.PRODUCER) ?: return null
        val since = maxOf(handled, history.record.value.lastSyncMillis ?: 0)
        val late = previous.toInstant().toEpochMilli() > since && Duration.between(previous, now) > MISSED_AFTER
        return previous.takeIf { late }
    }

    /** The due time a run starting now serves, or `null` for *whenever charging*. */
    fun servedDue(role: DeviceRole, schedule: SyncSchedule): ZonedDateTime? {
        if (schedule !is SyncSchedule.Timed) return null
        val now = ZonedDateTime.now(clock)
        val next = schedule.nextDue(now)
        // A receive window opens a little early: it serves the due time just ahead.
        return if (next != null && Duration.between(now, next) <= leadOf(role)) next else schedule.previousDue(now)
    }

    /** Called by a run that did its job: [due] won't run again, and the next run is queued behind this one. */
    suspend fun finished(role: DeviceRole, due: ZonedDateTime?) {
        due?.let { state.markHandled(role, it.toInstant().toEpochMilli()) }
        val plan = plans().first() ?: return
        if (plan.role != role || plan.schedule == SyncSchedule.Manual) return
        val delay = delayOf(plan)
        workManager.enqueueUniqueWork(nameOf(role), ExistingWorkPolicy.APPEND_OR_REPLACE, request(plan, delay))
        Diagnostics.log(Area.SCHEDULE, "Done with ${due ?: "this charge"}; next ${describe(plan, delay)}")
    }

    /** The current plan: the producer follows its consumer's schedule, the consumer its own. */
    internal fun plans(): Flow<BackgroundPlan?> = combine(
        settings.role,
        settings.syncSchedule,
        peers.observeConsumer().map { consumer -> consumer?.let { it.schedule ?: SyncSchedule.Default } },
    ) { role, own, consumers ->
        when (role) {
            DeviceRole.PRODUCER -> consumers?.let { BackgroundPlan(DeviceRole.PRODUCER, it) }
            DeviceRole.CONSUMER -> BackgroundPlan(DeviceRole.CONSUMER, own)
            null -> null
        }
    }

    internal suspend fun apply(plan: BackgroundPlan?) {
        DeviceRole.entries.filter { it != plan?.role }.forEach { workManager.cancelUniqueWork(nameOf(it)) }
        if (plan == null) {
            Diagnostics.log(Area.SCHEDULE, "Nothing runs in the background: no role, or not paired")
            return
        }
        if (plan.schedule == SyncSchedule.Manual) {
            workManager.cancelUniqueWork(nameOf(plan.role))
            Diagnostics.log(Area.SCHEDULE, "Nothing runs in the background: ${plan.role.name.lowercase()}, manual")
            return
        }
        // A run in progress queues the next one itself when it ends, with the schedule as it is then.
        if (isRunning(plan.role)) {
            Diagnostics.log(Area.SCHEDULE, "A ${plan.role.name.lowercase()} run is in progress; it queues the next")
            return
        }
        val delay = delayOf(plan)
        workManager.enqueueUniqueWork(nameOf(plan.role), ExistingWorkPolicy.REPLACE, request(plan, delay))
        Diagnostics.log(Area.SCHEDULE, "Next ${describe(plan, delay)}")
    }

    private fun describe(plan: BackgroundPlan, delay: Duration): String =
        "${plan.role.name.lowercase()} run (${plan.schedule.encode()}) in ${duration(delay)}, while charging"

    private suspend fun isRunning(role: DeviceRole) =
        workManager.getWorkInfosForUniqueWorkFlow(nameOf(role)).first().any { it.state == WorkInfo.State.RUNNING }

    internal fun request(plan: BackgroundPlan, delay: Duration = delayOf(plan)): OneTimeWorkRequest {
        val worker: Class<out ListenableWorker> = when (plan.role) {
            DeviceRole.PRODUCER -> ScheduledSyncWorker::class.java
            DeviceRole.CONSUMER -> ReceiveWindowWorker::class.java
        }
        return OneTimeWorkRequest.Builder(worker)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraintsOf(plan))
            // Unreachable consumer (its receive window may start late, see receiveWindowDone), or no Wi-Fi:
            // try again while charging (docs/PLAN.md §10.1).
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
    }

    private fun delayOf(plan: BackgroundPlan): Duration {
        val schedule = plan.schedule as? SyncSchedule.Timed ?: return Duration.ZERO
        val now = ZonedDateTime.now(clock)
        val previous = schedule.previousDue(now)?.toInstant()?.toEpochMilli()
        // First schedule on this phone: the current period counts as done, the first run is the next due time.
        val handled = state.lastHandledDue(plan.role) ?: previous?.also { state.markHandled(plan.role, it) }
        if (previous != null && handled != null && previous > handled) return Duration.ZERO // Missed: catch up.
        val next = schedule.nextDue(now) ?: return Duration.ZERO
        val stagger = when (plan.role) {
            DeviceRole.PRODUCER -> Duration.ofSeconds(Random.nextLong(STAGGER_SECONDS))
            DeviceRole.CONSUMER -> Duration.ZERO
        }
        return Duration.between(now, next).minus(leadOf(plan.role)).plus(stagger).coerceAtLeast(Duration.ZERO)
    }

    private fun constraintsOf(plan: BackgroundPlan): Constraints = Constraints.Builder()
        // No network constraint: Android only counts Wi-Fi with verified internet as a network, and phones on
        // a Wi-Fi without internet still reach each other. The workers check for a local network themselves.
        .setRequiresCharging(true)
        .apply {
            if (plan.role == DeviceRole.CONSUMER) setRequiresStorageNotLow(true)
            if (plan.role == DeviceRole.PRODUCER && plan.schedule == SyncSchedule.WhileCharging) {
                addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            }
        }
        .build()

    companion object {
        const val TAG = "mediasync-background"

        fun nameOf(role: DeviceRole) = when (role) {
            DeviceRole.PRODUCER -> "scheduled-sync"
            DeviceRole.CONSUMER -> "receive-window"
        }

        /** How long before a due time a receive window opens. */
        fun leadOf(role: DeviceRole): Duration =
            if (role == DeviceRole.CONSUMER) Duration.ofMinutes(WINDOW_LEAD_MINUTES) else Duration.ZERO

        val RETRY_INTERVAL: Duration = Duration.ofMinutes(15)
        private val MISSED_AFTER: Duration = Duration.ofHours(1)
        private const val SYNC_NOW = "sync-now"
        private const val RECEIVE_NOW = "receive-now"

        /** Every background job MediaSync has, for diagnostics. */
        val WORK_NAMES = listOf(nameOf(DeviceRole.PRODUCER), nameOf(DeviceRole.CONSUMER), SYNC_NOW, RECEIVE_NOW)
        private const val WINDOW_LEAD_MINUTES = 5L
        private const val STAGGER_SECONDS = 180L
    }
}
