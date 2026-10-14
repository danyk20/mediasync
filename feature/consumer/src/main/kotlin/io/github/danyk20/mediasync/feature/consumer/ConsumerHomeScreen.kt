/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.component.formatBytes
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme

@Composable
fun ConsumerHomeRoute(
    onAddProducer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConsumerHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConsumerHomeScreen(state = state, onAddProducer = onAddProducer, modifier = modifier)
}

/** Home screen of the phone that receives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumerHomeScreen(state: ConsumerHomeUiState, onAddProducer: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.consumer_home_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Status(state)
            Button(onClick = onAddProducer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.consumer_add_producer))
            }
            if (state.producers.isNotEmpty()) Producers(state.producers)
            Text(
                text = pluralStringResource(
                    R.plurals.consumer_received_count,
                    state.receivedCount,
                    state.receivedCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Status(state: ConsumerHomeUiState) {
    val transfer = state.transfer
    when {
        transfer != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusCard(
                title = stringResource(R.string.consumer_status_receiving_title, transfer.producerName),
                body = stringResource(
                    R.string.consumer_status_receiving_body,
                    transfer.itemsDone,
                    transfer.itemsTotal,
                    formatBytes(transfer.bytesDone),
                    formatBytes(transfer.bytesTotal),
                ),
                icon = MediaSyncIcons.Receive,
                tone = StatusTone.Positive,
            )
            LinearProgressIndicator(
                progress = {
                    if (transfer.bytesTotal ==
                        0L
                    ) {
                        0f
                    } else {
                        transfer.bytesDone.toFloat() / transfer.bytesTotal
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        !state.listening -> StatusCard(
            title = stringResource(R.string.consumer_status_offline_title),
            body = stringResource(R.string.consumer_status_offline_body),
            icon = MediaSyncIcons.Info,
        )

        state.producers.isEmpty() -> StatusCard(
            title = stringResource(R.string.consumer_status_ready_title),
            body = stringResource(R.string.consumer_status_ready_body),
            icon = MediaSyncIcons.Receive,
        )

        else -> StatusCard(
            title = stringResource(R.string.consumer_status_ready_title),
            body = stringResource(R.string.consumer_status_waiting_body),
            icon = MediaSyncIcons.Receive,
            tone = StatusTone.Positive,
        )
    }
}

@Composable
private fun Producers(names: List<String>) {
    Column {
        Text(
            text = stringResource(R.string.consumer_producers_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { heading() },
        )
        names.forEachIndexed { index, name ->
            if (index > 0) HorizontalDivider()
            ListItem(headlineContent = { Text(name) })
        }
    }
}

@Preview
@Composable
private fun ConsumerHomeScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        ConsumerHomeScreen(
            state = ConsumerHomeUiState(
                listening = true,
                transfer = IncomingTransfer("Anna's Pixel", 12, 40, 145_000_000, 830_000_000),
                producers = listOf("Anna's Pixel"),
                receivedCount = 1234,
            ),
            onAddProducer = {},
        )
    }
}
