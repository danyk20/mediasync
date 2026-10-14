/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a scheduled sync didn't get through; it tries again while the phone is charging. */
enum class SyncProblem {
    NO_WIFI,
    UNREACHABLE,
    CONNECTION_LOST,
    HOT,
    LOW_BATTERY,
}

/** A sending phone's past syncs, as its home screen shows them (docs/PLAN.md §10.3). */
data class SyncRecord(
    /** When the last sync ended with everything sent that could be. */
    val lastSyncMillis: Long? = null,
    /** Photos and videos the receiving phone had no space for in the last sync. */
    val waitingForSpace: Int = 0,
    /** Why the latest scheduled try didn't get through; `null` once a sync did. */
    val problem: SyncProblem? = null,
)

/** Keeps the [SyncRecord] across restarts: background syncs usually run while the app isn't open. */
@Singleton
class SyncHistory @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("sync_history", Context.MODE_PRIVATE)
    private val _record = MutableStateFlow(read())

    val record: StateFlow<SyncRecord> = _record.asStateFlow()

    fun recordSync(atMillis: Long, waitingForSpace: Int) = write {
        putLong(LAST_SYNC, atMillis)
        putInt(WAITING_FOR_SPACE, waitingForSpace)
        remove(PROBLEM)
    }

    fun recordProblem(problem: SyncProblem) = write { putString(PROBLEM, problem.name) }

    @Synchronized
    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        preferences.edit(action = change)
        _record.value = read()
    }

    private fun read() = SyncRecord(
        lastSyncMillis = preferences.getLong(LAST_SYNC, NONE).takeIf { it != NONE },
        waitingForSpace = preferences.getInt(WAITING_FOR_SPACE, 0),
        // A problem a newer app version wrote and this one doesn't know reads as none.
        problem = preferences.getString(PROBLEM, null)?.let { name ->
            SyncProblem.entries.firstOrNull { it.name == name }
        },
    )

    private companion object {
        const val LAST_SYNC = "last_sync"
        const val WAITING_FOR_SPACE = "waiting_for_space"
        const val PROBLEM = "problem"
        const val NONE = Long.MIN_VALUE
    }
}
