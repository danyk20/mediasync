/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.quickaccess

import android.content.Context
import android.text.format.DateUtils
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.android.SyncHistory
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What *Sync now* does from outside the app, on the Quick Settings tile and the home-screen widget (PLAN §17). */
enum class QuickAction {
    /** A sending phone, paired: send what's new now. */
    SYNC_NOW,

    /** A receiving phone with sending phones: open a receive window, so they can sync now. */
    RECEIVE_NOW,

    /** Not set up yet: open MediaSync. */
    OPEN_APP,
}

/** What the tile and the widget show: what tapping does, whether a sync runs, and when the last one was. */
data class QuickStatus(val action: QuickAction, val busy: Boolean = false, val lastSyncMillis: Long? = null)

/** *Sync now* from outside the app (docs/PLAN.md §3.3: quick access). */
@Singleton
class QuickSync @Inject constructor(
    settings: SettingsRepository,
    peers: PeerStore,
    producer: ProducerSync,
    receiver: ConsumerReceiver,
    history: SyncHistory,
    private val scheduler: SyncScheduler,
) {
    val status: Flow<QuickStatus> = combine(
        settings.role,
        peers.observeAll(),
        producer.state,
        receiver.state,
        history.record,
    ) { role, all, sending, receiving, record ->
        quickStatus(role, all.map { it.role }.toSet(), sending, receiving, record)
    }.distinctUntilChanged()

    /** Runs [action]; `false` for [QuickAction.OPEN_APP], which the caller does: opening an activity needs one. */
    fun run(action: QuickAction): Boolean = when (action) {
        QuickAction.SYNC_NOW -> true.also { scheduler.syncNow() }
        QuickAction.RECEIVE_NOW -> true.also { scheduler.receiveNow() }
        QuickAction.OPEN_APP -> false
    }
}

internal fun quickStatus(
    role: DeviceRole?,
    paired: Set<DeviceRole>,
    sending: ProducerState,
    receiving: ReceiverState,
    record: SyncRecord,
): QuickStatus = when {
    role == DeviceRole.PRODUCER && DeviceRole.CONSUMER in paired -> QuickStatus(
        QuickAction.SYNC_NOW,
        busy = sending !is ProducerState.Idle && sending !is ProducerState.Failed,
        lastSyncMillis = record.lastSyncMillis,
    )

    role == DeviceRole.CONSUMER && DeviceRole.PRODUCER in paired -> QuickStatus(
        QuickAction.RECEIVE_NOW,
        busy = receiving.transfers.isNotEmpty(),
        lastSyncMillis = receiving.lastActivityMillis,
    )

    else -> QuickStatus(QuickAction.OPEN_APP)
}

/** The tile's and the widget's words: what tapping does, and how syncing stands. */
data class QuickLabels(val action: String, val detail: String)

internal fun Context.quickLabels(status: QuickStatus, nowMillis: Long = System.currentTimeMillis()): QuickLabels {
    val action = when (status.action) {
        QuickAction.SYNC_NOW -> getString(R.string.quick_sync_now)
        QuickAction.RECEIVE_NOW -> getString(R.string.quick_receive_now)
        QuickAction.OPEN_APP -> getString(R.string.quick_open_app)
    }
    val last = status.lastSyncMillis?.let {
        getString(
            R.string.quick_last_sync,
            DateUtils.getRelativeTimeSpanString(it, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
    val detail = when (status.action) {
        QuickAction.OPEN_APP -> getString(R.string.quick_not_set_up)

        QuickAction.SYNC_NOW ->
            if (status.busy) getString(R.string.quick_syncing) else last ?: getString(R.string.quick_never_synced)

        QuickAction.RECEIVE_NOW ->
            if (status.busy) getString(R.string.quick_receiving) else last ?: getString(R.string.quick_ready_to_receive)
    }
    return QuickLabels(action, detail)
}
