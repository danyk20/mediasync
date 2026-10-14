/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.core.model.SyncSchedule
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A sending phone's scheduled sync (docs/PLAN.md §10.2). WorkManager starts it at the due time once the phone
 * is charging; unplugging stops it after the current file, and it carries on at the next charge.
 */
@HiltWorker
class ScheduledSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: ProducerSync,
    private val peers: PeerStore,
    private val network: AddressProvider,
    private val scheduler: SyncScheduler,
    private val notifications: SyncNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val consumer = withContext(Dispatchers.IO) { peers.consumer() } ?: return Result.success() // Unpaired.
        // Phones reach each other on the local network only: without Wi-Fi, try again later.
        if (network.localAddresses().isEmpty()) return Result.retry()
        val due = scheduler.servedDue(DeviceRole.PRODUCER, consumer.schedule ?: SyncSchedule.Default)
        notifications.runInForeground(this, SyncNotifications.Kind.SENDING)
        val end = sync.syncAndWait()
        if (end is ProducerState.Failed && end.reason in RETRIED) return Result.retry()
        // Done, or nothing to retry until the user acts (e.g. pair again); the home screen says why.
        scheduler.finished(DeviceRole.PRODUCER, due)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(SyncNotifications.Kind.SENDING)

    private companion object {
        /** The receiving phone wasn't there, or the connection dropped: worth another try while charging. */
        val RETRIED = setOf(SyncFailure.UNREACHABLE, SyncFailure.CONNECTION_LOST)
    }
}

/**
 * A receiving phone's receive window (docs/PLAN.md §10.1): listens from shortly before the due time until every
 * sending phone is done, that is after a quiet hour, at most 4 hours. With *whenever charging* it listens
 * until the phone is unplugged; WorkManager opens it again at the next charge.
 */
@HiltWorker
class ReceiveWindowWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receiver: ConsumerReceiver,
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
    private val notifications: SyncNotifications,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val schedule = settings.syncSchedule.first()
        val due = scheduler.servedDue(DeviceRole.CONSUMER, schedule)
        notifications.runInForeground(this, SyncNotifications.Kind.RECEIVING)
        val opened = clock.millis()
        receiver.hold(HOLDER)
        try {
            while (!windowDone(due, opened)) delay(CHECK_INTERVAL.toMillis())
        } finally {
            receiver.release(HOLDER)
        }
        scheduler.finished(DeviceRole.CONSUMER, due)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(SyncNotifications.Kind.RECEIVING)

    private fun windowDone(due: ZonedDateTime?, opened: Long) =
        receiveWindowDone(due, opened, clock.millis(), receiver.state.value)

    private companion object {
        const val HOLDER = "receive-window"
        val CHECK_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}

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
 * The ongoing notification of background syncs. Android shows it while a sync runs as a foreground service,
 * which lets it run longer than 10 minutes.
 */
@ExercisedOnDevice(coveredBy = "app MainActivityTest (smoke) and the two-emulator test")
@Singleton
class SyncNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    enum class Kind(val id: Int, @StringRes val title: Int, @DrawableRes val icon: Int) {
        SENDING(SENDING_ID, R.string.sync_notification_sending, android.R.drawable.stat_sys_upload),
        RECEIVING(RECEIVING_ID, R.string.sync_notification_receiving, android.R.drawable.stat_sys_download),
    }

    fun foregroundInfo(kind: Kind): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(kind.icon)
            .setContentTitle(context.getString(kind.title))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(kind.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * Runs [worker] as a foreground service when Android allows it. Android 12+ allows it from the background
     * only with an exemption (battery set to *Unrestricted*, docs/PLAN.md §10.3); without one the work carries
     * on as ordinary background work, which Android stops after 10 minutes and resumes later.
     */
    suspend fun runInForeground(worker: CoroutineWorker, kind: Kind) {
        try {
            worker.setForeground(foregroundInfo(kind))
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is one.
        }
    }

    private companion object {
        const val CHANNEL = "background-sync"
        const val SENDING_ID = 1
        const val RECEIVING_ID = 2
    }
}
