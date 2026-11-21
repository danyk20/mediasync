/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import androidx.work.WorkInfo

/** Why Android stopped background work, from [WorkInfo]'s stop reasons. */
internal fun stopReason(reason: Int): String = when (reason) {
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "unplugged"
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage low"
    WorkInfo.STOP_REASON_TIMEOUT -> "time limit"
    WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "daily foreground limit (Android 15+)"
    WorkInfo.STOP_REASON_QUOTA -> "job quota"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background restriction"
    WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "device state (Doze or battery saver)"
    WorkInfo.STOP_REASON_PREEMPT -> "pre-empted"
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by MediaSync"
    WorkInfo.STOP_REASON_USER -> "stopped by the user"
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
    else -> "reason $reason"
}
