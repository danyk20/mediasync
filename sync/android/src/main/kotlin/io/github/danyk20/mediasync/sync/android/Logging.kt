/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import androidx.work.WorkInfo
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import java.time.Duration
import java.util.Locale

// How the sync runtime describes things in the diagnostic log (core:diagnostics): plain, short, and without
// names of files, places or people.

/** A phone, by the start of its id: enough to tell phones apart in a log. */
internal fun DeviceId.short(): String = hex.take(SHORT_ID)

internal fun bytes(count: Long): String = when {
    count >= GB -> String.format(Locale.ROOT, "%.2f GB", count.toDouble() / GB)
    count >= MB -> String.format(Locale.ROOT, "%.1f MB", count.toDouble() / MB)
    count >= KB -> String.format(Locale.ROOT, "%.1f kB", count.toDouble() / KB)
    else -> "$count B"
}

internal fun since(startMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    String.format(Locale.ROOT, "%.1f s", (nowMillis - startMillis) / MILLIS_PER_SECOND)

internal fun duration(duration: Duration): String {
    val minutes = duration.toMinutes()
    return when {
        minutes >= MINUTES_PER_HOUR -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        minutes > 0 -> "$minutes min"
        else -> "${duration.seconds} s"
    }
}

internal fun TransferProgress?.describe(): String =
    this?.let { "item $itemsDone of $itemsTotal, ${bytes(bytesDone)} of ${bytes(bytesTotal)}" }
        ?: "before the first item"

internal fun SyncResult.describe(): String = buildList {
    add("sent $sent (${bytes(bytesSent)})")
    if (alreadyOnConsumer > 0) add("already there $alreadyOnConsumer")
    if (failed > 0) add("failed $failed")
    if (deferred > 0) add("no space for $deferred")
    if (withheld > 0) add("withheld $withheld")
    if (cancelled) add("stopped early")
}.joinToString(", ")

internal fun ServeResult.describe(): String = when (this) {
    is ServeResult.Synced -> buildList {
        add("received $received (${bytes(bytesReceived)})")
        if (alreadyHad > 0) add("already had $alreadyHad")
        if (failed > 0) add("failed $failed")
        if (waitingForSpace > 0) add("no space for $waitingForSpace")
    }.joinToString(", ")

    is ServeResult.Paired -> "paired"

    is ServeResult.PairingRefused -> "pairing refused (${decision.name.lowercase()})"

    is ServeResult.Rejected -> "rejected (reason $byeReason)"
}

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

private const val SHORT_ID = 6
private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024
private const val MILLIS_PER_SECOND = 1000.0
private const val MINUTES_PER_HOUR = 60L
