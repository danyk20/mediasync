/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

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
