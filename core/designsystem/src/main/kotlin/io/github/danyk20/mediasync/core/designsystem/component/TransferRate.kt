/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.danyk20.mediasync.core.designsystem.R

private const val MINUTES_PER_HOUR = 60

/**
 * A transfer's speed, and the time left when it's known: "8.4 MB/s · about 2 min left". [minutesLeft] is 0 under a
 * minute, and `null` when not known (a receiving phone learns the total only batch by batch). At 0 B/s the transfer
 * waits for the connection.
 */
@Composable
fun transferRate(bytesPerSecond: Long, minutesLeft: Int?): String {
    if (bytesPerSecond <= 0) return stringResource(R.string.transfer_waiting)
    val speed = stringResource(R.string.transfer_speed, formatBytes(bytesPerSecond))
    val left = when {
        minutesLeft == null -> return speed

        minutesLeft == 0 -> stringResource(R.string.transfer_left_soon)

        minutesLeft < MINUTES_PER_HOUR -> stringResource(R.string.transfer_left_minutes, minutesLeft)

        minutesLeft % MINUTES_PER_HOUR == 0 ->
            stringResource(R.string.transfer_left_whole_hours, minutesLeft / MINUTES_PER_HOUR)

        else -> stringResource(
            R.string.transfer_left_hours,
            minutesLeft / MINUTES_PER_HOUR,
            minutesLeft % MINUTES_PER_HOUR,
        )
    }
    return stringResource(R.string.transfer_speed_and_left, speed, left)
}
