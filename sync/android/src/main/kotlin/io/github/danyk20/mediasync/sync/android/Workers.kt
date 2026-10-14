/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A sending phone's scheduled sync (docs/PLAN.md §10.2). WorkManager starts it at the due time once the phone
 * is charging; unplugging stops it after the current file, and it carries on at the next charge. A hot phone or
 * a battery below 20 % pauses it until later (§10.5).
 *
 * Android 15+ also stops it after 6 hours of syncing a day (§10.3). Everything confirmed so far stays sent, and
 * WorkManager runs it again later, which carries on where it stopped.
 */
@HiltWorker
class ScheduledSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: ProducerSync,
    private val peers: PeerStore,
    private val network: AddressProvider,
    private val conditions: DeviceConditions,
    private val scheduler: SyncScheduler,
    private val history: SyncHistory,
    private val notifications: SyncNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val consumer = withContext(Dispatchers.IO) { peers.consumer() } ?: return Result.success() // Unpaired.
        val problem = sync(consumer) ?: return Result.success()
        // Tries again while charging; the home screen says why if the sync is late.
        history.recordProblem(problem)
        return Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.sending(consumerName = null)

    /** Syncs, unless something is in the way: then returns it, and the due time is still to do. */
    private suspend fun sync(consumer: Peer): SyncProblem? {
        // Phones reach each other on the local network only: without Wi-Fi, try again later.
        if (network.localAddresses().isEmpty()) return SyncProblem.NO_WIFI
        conditions.pauseReason()?.let { return it.problem }
        val due = scheduler.servedDue(DeviceRole.PRODUCER, consumer.schedule ?: SyncSchedule.Default)
        var paused: PauseReason? = null
        val end = send(sync, notifications, consumer.name) {
            sync.syncAndWait(pauseWhen = { conditions.pauseReason()?.also { paused = it } != null })
        }
        val problem = paused?.problem ?: (end as? ProducerState.Failed)?.let { RETRIED[it.reason] }
        // Done, or nothing to retry until the user acts (e.g. pair again); the home screen says why.
        if (problem == null) scheduler.finished(DeviceRole.PRODUCER, due)
        return problem
    }

    private val PauseReason.problem
        get() = when (this) {
            PauseReason.HOT -> SyncProblem.HOT
            PauseReason.LOW_BATTERY -> SyncProblem.LOW_BATTERY
        }

    private companion object {
        /** The receiving phone wasn't there, or the connection dropped: worth another try while charging. */
        val RETRIED = mapOf(
            SyncFailure.UNREACHABLE to SyncProblem.UNREACHABLE,
            SyncFailure.CONNECTION_LOST to SyncProblem.CONNECTION_LOST,
        )
    }
}

/**
 * *Sync now* (docs/PLAN.md §10.2): sends as expedited work in the foreground, so it carries on when the user
 * leaves the app. The user asked for it, so it doesn't wait for charging or better conditions.
 */
@HiltWorker
class SyncNowWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: ProducerSync,
    private val peers: PeerStore,
    private val notifications: SyncNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val consumer = withContext(Dispatchers.IO) { peers.consumer() }
        send(sync, notifications, consumer?.name) { sync.syncAndWait() }
        return Result.success() // However it went, the home screen shows it.
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.sending(consumerName = null)
}

/** Runs [syncing] in the foreground where Android allows it, with live progress and a note of what was sent. */
private suspend fun CoroutineWorker.send(
    sync: ProducerSync,
    notifications: SyncNotifications,
    consumerName: String?,
    syncing: suspend () -> ProducerState,
): ProducerState {
    notifications.runInForeground(this, notifications.sending(consumerName))
    val end = coroutineScope {
        val progress = launch { notifications.showSendingProgress(consumerName, sync.state) }
        try {
            syncing()
        } finally {
            progress.cancel()
        }
    }
    val result = (end as? ProducerState.Idle)?.lastResult
    if (result != null && consumerName != null) notifications.showSent(consumerName, result.sent)
    return end
}

/**
 * A receiving phone's receive window (docs/PLAN.md §10.1): listens from shortly before the due time until every
 * sending phone is done, that is after a quiet hour, at most 4 hours. With *whenever charging* it listens
 * until the phone is unplugged; WorkManager opens it again at the next charge. A hot phone or a battery below
 * 20 % closes it until later (§10.5); sending phones then try again.
 */
@HiltWorker
class ReceiveWindowWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receiver: ConsumerReceiver,
    private val settings: SettingsRepository,
    private val peers: PeerStore,
    private val conditions: DeviceConditions,
    private val scheduler: SyncScheduler,
    private val notifications: SyncNotifications,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (conditions.pauseReason() != null) return Result.retry()
        val schedule = settings.syncSchedule.first()
        val due = scheduler.servedDue(DeviceRole.CONSUMER, schedule)
        notifications.runInForeground(this, notifications.receiving())
        val opened = clock.millis()
        val paused = coroutineScope {
            receiver.hold(HOLDER)
            val progress = launch { notifications.showReceivingProgress(incoming()) }
            try {
                while (!receiveWindowDone(due, opened, clock.millis(), receiver.state.value)) {
                    if (conditions.pauseReason() != null) return@coroutineScope true
                    delay(CHECK_INTERVAL.toMillis())
                }
                false
            } finally {
                progress.cancel()
                receiver.release(HOLDER)
            }
        }
        if (paused) return Result.retry()
        scheduler.finished(DeviceRole.CONSUMER, due)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.receiving()

    /** The transfer the notification shows, with the sending phone's name. */
    private fun incoming(): Flow<Incoming?> = receiver.state.map { state ->
        state.transfers.entries.firstOrNull()?.let { (producer, progress) ->
            val known = withContext(Dispatchers.IO) { peers.observeAll().first() }
            Incoming(known.firstOrNull { it.id == producer }?.name, progress)
        }
    }

    private companion object {
        const val HOLDER = "receive-window"
        val CHECK_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}

/** A transfer to this phone, as its notification shows it. */
data class Incoming(val producerName: String?, val progress: TransferProgress)

/**
 * Whether a receive window that serves [due] and opened at [opened] can close at [now]: once no transfer is
 * running and nothing happened for an hour since the due time (or since it opened, if it opened late), and
 * after 4 hours in any case. `due == null` is *whenever charging*: it stays open until the phone is unplugged.
 *
 * Why an hour: Android 14+ batches background jobs, so the window and the sending phones' syncs may each start
 * up to ~31 minutes after their time. A sending phone that came too early retries every 15 minutes; the hour
 * leaves it at least two tries while the window is open.
 */
internal fun receiveWindowDone(due: ZonedDateTime?, opened: Long, now: Long, state: ReceiverState): Boolean {
    if (due == null) return false
    if (now - opened >= MAX_WINDOW.toMillis()) return true
    val quietSince = maxOf(due.toInstant().toEpochMilli(), opened, state.lastActivityMillis ?: 0)
    return state.transfers.isEmpty() && now - quietSince >= QUIET.toMillis()
}

private val QUIET: Duration = Duration.ofMinutes(60)
private val MAX_WINDOW: Duration = Duration.ofHours(4)

/**
 * The notifications of background syncs: the ongoing one while a sync runs, with live progress, and a short one
 * saying what was sent. While a sync runs as a foreground service, which lets it run longer than 10 minutes, the
 * ongoing notification is the service's.
 *
 * Android 13+ shows them only with the user's permission, which the home screens ask for; without it, syncs run
 * all the same.
 */
@ExercisedOnDevice(coveredBy = "the two-emulator test")
@Singleton
class SyncNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    fun sending(consumerName: String?): ForegroundInfo = foreground(SENDING_ID, sendingNotification(consumerName, null))

    fun receiving(): ForegroundInfo = foreground(RECEIVING_ID, receivingNotification(null))

    /**
     * Runs [worker] as a foreground service when Android allows it. Android 12+ allows it from the background
     * only with an exemption (battery set to *Unrestricted*, docs/PLAN.md §10.3); without one the work carries
     * on as ordinary background work, which Android stops after 10 minutes and resumes later.
     */
    suspend fun runInForeground(worker: CoroutineWorker, info: ForegroundInfo) {
        try {
            worker.setForeground(info)
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is one.
        }
    }

    /** Keeps the ongoing notification current until cancelled, then removes it. */
    suspend fun showSendingProgress(consumerName: String?, states: Flow<ProducerState>) =
        showProgress(SENDING_ID, states) { state ->
            (state as? ProducerState.Syncing)?.progress?.let { sendingNotification(consumerName, it) }
        }

    /** Keeps the ongoing notification current until cancelled, then removes it. */
    suspend fun showReceivingProgress(incoming: Flow<Incoming?>) =
        showProgress(RECEIVING_ID, incoming) { receivingNotification(it) }

    fun showSent(consumerName: String, count: Int) {
        if (count == 0) return
        val notification = builder(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.resources.getQuantityString(R.plurals.sync_notification_sent, count, count))
            .setContentText(context.getString(R.string.sync_notification_sent_to, consumerName))
            .setAutoCancel(true)
            .build()
        post(SENT_ID, notification)
    }

    private suspend fun <T> showProgress(id: Int, states: Flow<T>, notificationOf: (T) -> Notification?) {
        try {
            states.collect { state ->
                notificationOf(state)?.let { post(id, it) }
                delay(PROGRESS_INTERVAL.toMillis()) // StateFlows skip what came meanwhile.
            }
        } finally {
            NotificationManagerCompat.from(context).cancel(id)
        }
    }

    private fun sendingNotification(consumerName: String?, progress: TransferProgress?) = ongoing(
        icon = android.R.drawable.stat_sys_upload,
        title = consumerName?.let { context.getString(R.string.sync_notification_sending_to, it) }
            ?: context.getString(R.string.sync_notification_sending),
        progress = progress,
    )

    private fun receivingNotification(incoming: Incoming?) = ongoing(
        icon = android.R.drawable.stat_sys_download,
        title = when {
            incoming == null -> context.getString(R.string.sync_notification_ready)
            incoming.producerName == null -> context.getString(R.string.sync_notification_receiving)
            else -> context.getString(R.string.sync_notification_receiving_from, incoming.producerName)
        },
        progress = incoming?.progress,
    )

    private fun ongoing(@DrawableRes icon: Int, title: String, progress: TransferProgress?): Notification =
        builder(icon)
            .setContentTitle(title)
            .apply {
                if (progress != null) {
                    setContentText(
                        context.getString(R.string.sync_notification_progress, progress.itemsDone, progress.itemsTotal),
                    )
                    setProgress(PROGRESS_MAX, progress.permille(), progress.bytesTotal == 0L)
                }
            }
            .setOngoing(true)
            .build()

    private fun builder(@DrawableRes icon: Int): NotificationCompat.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentIntent(open)
            .setSilent(true)
    }

    private fun foreground(id: Int, notification: Notification) =
        ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun post(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(id, notification)
            } catch (_: SecurityException) {
                // The permission was revoked just now.
            }
        }
    }

    private fun TransferProgress.permille(): Int =
        if (bytesTotal == 0L) 0 else (bytesDone * PROGRESS_MAX / bytesTotal).toInt()

    private companion object {
        const val CHANNEL = "background-sync"
        const val SENDING_ID = 1
        const val RECEIVING_ID = 2
        const val SENT_ID = 3
        const val PROGRESS_MAX = 1000

        /** Progress notifications change at most this often; Android drops faster updates anyway. */
        val PROGRESS_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
