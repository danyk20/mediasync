/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.R
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons

/** A transfer's speed and time left, with an ⓘ that opens its speed over time. */
@Composable
fun TransferRateRow(text: String, onShowSpeeds: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onShowSpeeds) {
            Icon(
                painter = painterResource(MediaSyncIcons.Info),
                contentDescription = stringResource(R.string.speed_chart_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The speed over a transfer, in a sheet from the bottom of the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(points: List<SpeedPoint>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SpeedOverview(points, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp))
    }
}

/** The speed now, on average and at the peak, then how it went over time. */
@Composable
fun SpeedOverview(points: List<SpeedPoint>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.speed_chart_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (points.isEmpty()) {
            Text(
                text = stringResource(R.string.speed_chart_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val stats = SpeedStats.of(points)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(R.string.speed_chart_now, stats.now)
            Stat(R.string.speed_chart_average, stats.average)
            Stat(R.string.speed_chart_peak, stats.peak)
        }
        SpeedChart(points)
        Text(
            text = stringResource(R.string.speed_chart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(@StringRes label: Int, bytesPerSecond: Long) {
    Column {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.transfer_speed, formatBytes(bytesPerSecond)),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
