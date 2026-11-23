/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.LinkSpeed
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.RelayCounter
import java.time.Duration
import java.util.Locale

// How the sync runtime describes things in the diagnostic log (core:diagnostics): plain, short, and without
// names of files, places or people.

/** A phone, by the start of its id: enough to tell phones apart in a log. */
fun DeviceId.short(): String = hex.take(SHORT_ID)

fun bytes(count: Long): String = when {
    count >= GB -> String.format(Locale.ROOT, "%.2f GB", count.toDouble() / GB)
    count >= MB -> String.format(Locale.ROOT, "%.1f MB", count.toDouble() / MB)
    count >= KB -> String.format(Locale.ROOT, "%.1f kB", count.toDouble() / KB)
    else -> "$count B"
}

fun since(startMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    String.format(Locale.ROOT, "%.1f s", (nowMillis - startMillis) / MILLIS_PER_SECOND)

fun duration(duration: Duration): String {
    val minutes = duration.toMinutes()
    return when {
        minutes >= MINUTES_PER_HOUR -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        minutes > 0 -> "$minutes min"
        else -> "${duration.seconds} s"
    }
}

fun TransferProgress?.describe(): String =
    this?.let { "item $itemsDone of $itemsTotal, ${bytes(bytesDone)} of ${bytes(bytesTotal)}" }
        ?: "before the first item"

fun SyncResult.describe(): String = buildList {
    add("sent $sent (${bytes(bytesSent)})")
    if (alreadyOnConsumer > 0) add("already there $alreadyOnConsumer")
    if (failed > 0) add("failed $failed")
    if (deferred > 0) add("no space for $deferred")
    if (heldBack > 0) add("held back $heldBack")
    if (withheld > 0) add("withheld $withheld")
    if (cancelled) add("stopped early")
}.joinToString(", ")

fun ServeResult.describe(): String = when (this) {
    is ServeResult.Synced -> buildList {
        add("received $received (${bytes(bytesReceived)})")
        if (alreadyHad > 0) add("already had $alreadyHad")
        if (failed > 0) add("failed $failed")
        if (waitingForSpace > 0) add("no space for $waitingForSpace")
    }.joinToString(", ")

    is ServeResult.Paired -> "paired"

    is ServeResult.PairingRefused -> "pairing refused (${decision.name.lowercase()})"

    is ServeResult.Rejected -> "rejected (reason $byeReason)"

    is ServeResult.LinkTested -> "link test, ${LinkSpeed(bytes, micros).describe()}"

    is ServeResult.TurboAnswered -> "Turbo ${answer.describe()}"
}

fun LinkSpeed.describe(): String = String.format(
    Locale.ROOT,
    "%.1f MB/s (%s in %s)",
    megabytesPerSecond,
    bytes(bytes),
    since(
        0,
        micros / MICROS_PER_MILLI,
    ),
)

private const val SHORT_ID = 6
private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024
private const val MILLIS_PER_SECOND = 1000.0
private const val MINUTES_PER_HOUR = 60L
private const val MICROS_PER_MILLI = 1000L

fun TurboAnswer.describe(): String = when (this) {
    is TurboAnswer.Ready ->
        "ready on ${link.networkName}" +
            if (link.kind == LinkKind.WIFI_AWARE) " (Wi-Fi Aware)" else ""

    is TurboAnswer.Unavailable -> "unavailable (${reason.name.lowercase()})"
}

/** How an internet connection runs, for the log: directly between the phones, or through the relay (PLAN §8.4). */
fun PeerConnection.internetRoute(): String =
    if ((this as? RelayCounter)?.throughRelay() == true) "through the relay" else "directly"
