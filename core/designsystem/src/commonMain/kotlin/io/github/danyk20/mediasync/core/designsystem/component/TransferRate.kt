/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_left_hours
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_left_minutes
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_left_seconds
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_left_whole_hours
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_measuring
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_speed
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_speed_and_left
import io.github.danyk20.mediasync.core.designsystem.generated.resources.transfer_waiting
import org.jetbrains.compose.resources.stringResource

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

/**
 * A transfer's speed, and the time left when it's known: "8.4 MB/s · about 2 min left". [bytesPerSecond] is `null`
 * while it's still measured, in a transfer's first seconds. [secondsLeft] is as people say it (whole minutes, or tens
 * of seconds in the last minute), and `null` when not known (a receiving phone learns the total only batch by batch).
 * At 0 B/s the transfer waits for the connection.
 */
@Composable
fun transferRate(bytesPerSecond: Long?, secondsLeft: Int?): String {
    if (bytesPerSecond == null) return stringResource(Res.string.transfer_measuring)
    if (bytesPerSecond <= 0) return stringResource(Res.string.transfer_waiting)
    val speed = stringResource(Res.string.transfer_speed, formatBytes(bytesPerSecond))
    if (secondsLeft == null) return speed
    val minutesLeft = secondsLeft / SECONDS_PER_MINUTE
    val left = when {
        minutesLeft == 0 -> stringResource(Res.string.transfer_left_seconds, secondsLeft)

        minutesLeft < MINUTES_PER_HOUR -> stringResource(Res.string.transfer_left_minutes, minutesLeft)

        minutesLeft % MINUTES_PER_HOUR == 0 ->
            stringResource(Res.string.transfer_left_whole_hours, minutesLeft / MINUTES_PER_HOUR)

        else -> stringResource(
            Res.string.transfer_left_hours,
            minutesLeft / MINUTES_PER_HOUR,
            minutesLeft % MINUTES_PER_HOUR,
        )
    }
    return stringResource(Res.string.transfer_speed_and_left, speed, left)
}
