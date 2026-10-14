/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.component.formatBytes
import io.github.danyk20.mediasync.core.designsystem.component.relativeMoment
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import java.text.DateFormat
import java.util.Date

@Composable
fun ProducerHomeRoute(
    onPair: () -> Unit,
    onChooseFolders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProducerHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsChanged()
    }
    // Back from system settings, or permissions changed elsewhere.
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionsChanged()
        onPauseOrDispose {}
    }
    ProducerHomeScreen(
        state = state,
        onPair = onPair,
        onChooseFolders = onChooseFolders,
        onSyncNow = viewModel::syncNow,
        onStop = viewModel::stop,
        onGrantAccess = { requestPermissions.launch(MediaPermissions.required.toTypedArray()) },
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
        modifier = modifier,
    )
}

/** Home screen of a phone that sends. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProducerHomeScreen(
    state: ProducerHomeUiState,
    onPair: () -> Unit,
    onChooseFolders: () -> Unit,
    onSyncNow: () -> Unit,
    onStop: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.producer_home_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val consumer = state.consumerName
            when {
                consumer == null -> NotPaired(onPair)

                !state.canReadLibrary -> NeedsAccess(consumer, onGrantAccess, onOpenSettings)

                else -> {
                    Paired(consumer, state, onSyncNow, onStop)
                    SourcesRow(state.sources, onChooseFolders)
                    Hints(state, consumer, onGrantAccess)
                }
            }
        }
    }
}

@Composable
private fun NotPaired(onPair: () -> Unit) {
    StatusCard(
        title = stringResource(R.string.producer_status_not_paired_title),
        body = stringResource(R.string.producer_status_not_paired_body),
        icon = MediaSyncIcons.Info,
    )
    Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.producer_pair)) }
}

@Composable
private fun NeedsAccess(consumer: String, onGrantAccess: () -> Unit, onOpenSettings: () -> Unit) {
    StatusCard(
        title = stringResource(R.string.producer_access_title),
        body = stringResource(R.string.producer_access_body, consumer),
        icon = MediaSyncIcons.Info,
    )
    Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.producer_access_grant))
    }
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.producer_access_settings))
    }
}

@Composable
private fun Paired(consumer: String, state: ProducerHomeUiState, onSyncNow: () -> Unit, onStop: () -> Unit) {
    when (val sync = state.sync) {
        is ProducerState.Syncing -> {
            Sending(consumer, sync.progress)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.producer_stop))
            }
        }

        ProducerState.Connecting -> StatusCard(
            title = stringResource(R.string.producer_status_connecting_title, consumer),
            body = stringResource(R.string.producer_status_connecting_body),
            icon = MediaSyncIcons.Send,
        )

        is ProducerState.Failed -> {
            StatusCard(
                title = stringResource(R.string.producer_status_failed_title),
                body = failureMessage(sync.reason, consumer),
                icon = MediaSyncIcons.Info,
            )
            SyncNowButton(onSyncNow)
        }

        is ProducerState.Idle -> {
            if (state.sources.isEmpty()) {
                StatusCard(
                    title = stringResource(R.string.producer_status_no_folders_title),
                    body = stringResource(R.string.producer_status_no_folders_body, consumer),
                    icon = MediaSyncIcons.Info,
                )
            } else {
                IdleStatus(consumer, state.pendingCount, sync.finishedAtMillis)
                SyncNowButton(onSyncNow)
            }
        }
    }
}

/** When this phone syncs next, following the receiving phone's schedule. */
@Composable
private fun NextSync(state: ProducerHomeUiState) {
    val text = when (val schedule = state.schedule) {
        null -> return

        is SyncSchedule.Timed -> {
            val at = state.nextSyncAt ?: return
            stringResource(R.string.producer_next_sync, relativeMoment(at, state.now ?: at))
        }

        SyncSchedule.WhileCharging -> stringResource(R.string.producer_sync_while_charging)

        SyncSchedule.Manual -> stringResource(R.string.producer_sync_manual)
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** Which folders are sent, and the way to change them. */
@Composable
private fun SourcesRow(sources: SourceSelection, onChooseFolders: () -> Unit) {
    OutlinedCard(onClick = onChooseFolders, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.producer_sources_title)) },
            supportingContent = { Text(sourcesSummary(sources)) },
            leadingContent = { Icon(painterResource(MediaSyncIcons.Folder), contentDescription = null) },
            trailingContent = {
                Text(
                    text = stringResource(R.string.producer_sources_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** "Camera only", or the chosen folders' names: "Camera, WhatsApp Images and 2 more". */
@Composable
private fun sourcesSummary(sources: SourceSelection): String {
    sources.preset?.let { return presetName(it) }
    val names = sources.folders.map(SourceSelection::nameOf).sortedWith(String.CASE_INSENSITIVE_ORDER)
    return when {
        names.isEmpty() -> stringResource(R.string.producer_sources_none)

        names.size <= SUMMARY_NAMES -> names.joinToString(", ")

        else -> {
            val more = names.size - SUMMARY_NAMES
            pluralStringResource(
                R.plurals.producer_sources_more,
                more,
                names.take(SUMMARY_NAMES).joinToString(", "),
                more,
            )
        }
    }
}

private const val SUMMARY_NAMES = 2

private fun SourceSelection.isEmpty() = preset == null && folders.isEmpty()

@Composable
private fun Hints(state: ProducerHomeUiState, consumer: String, onGrantAccess: () -> Unit) {
    NextSync(state)
    if (!state.canReadLocation) {
        Text(
            text = stringResource(R.string.producer_location_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onGrantAccess) { Text(stringResource(R.string.producer_location_grant)) }
    }
    Text(
        text = stringResource(R.string.producer_keep_open_hint, consumer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SyncNowButton(onSyncNow: () -> Unit) {
    Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.producer_sync_now)) }
}

@Composable
private fun Sending(consumer: String, progress: TransferProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(
            title = stringResource(R.string.producer_status_sending_title, consumer),
            body = if (progress == null) {
                stringResource(R.string.producer_status_sending_preparing)
            } else {
                stringResource(
                    R.string.producer_status_sending_body,
                    progress.itemsDone,
                    progress.itemsTotal,
                    formatBytes(progress.bytesDone),
                    formatBytes(progress.bytesTotal),
                )
            },
            icon = MediaSyncIcons.Send,
            tone = StatusTone.Positive,
        )
        if (progress == null || progress.bytesTotal == 0L) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress.bytesDone.toFloat() / progress.bytesTotal },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IdleStatus(consumer: String, pending: Int?, finishedAtMillis: Long?) {
    val lastSync = finishedAtMillis?.let {
        stringResource(R.string.producer_last_sync, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
    }
    when {
        pending == null -> StatusCard(
            title = stringResource(R.string.producer_status_checking_title),
            body = stringResource(R.string.producer_status_checking_body, consumer),
            icon = MediaSyncIcons.Send,
        )

        pending == 0 -> StatusCard(
            title = stringResource(R.string.producer_status_up_to_date_title),
            body = lastSync ?: stringResource(R.string.producer_status_up_to_date_body, consumer),
            icon = MediaSyncIcons.Send,
            tone = StatusTone.Positive,
        )

        else -> StatusCard(
            title = pluralStringResource(R.plurals.producer_status_pending_title, pending, pending),
            body = pluralStringResource(R.plurals.producer_status_pending_body, pending, consumer),
            icon = MediaSyncIcons.Send,
        )
    }
}

@Composable
private fun failureMessage(reason: SyncFailure, consumer: String): String = when (reason) {
    SyncFailure.NOT_PAIRED -> stringResource(R.string.producer_status_not_paired_body)
    SyncFailure.UNREACHABLE -> stringResource(R.string.producer_failure_unreachable, consumer)
    SyncFailure.CONNECTION_LOST -> stringResource(R.string.producer_failure_connection_lost, consumer)
    SyncFailure.NOT_ALLOWED -> stringResource(R.string.producer_failure_not_allowed, consumer)
    SyncFailure.INCOMPATIBLE -> stringResource(R.string.producer_failure_incompatible, consumer)
}

@Preview
@Composable
private fun ProducerHomeScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        ProducerHomeScreen(
            state = ProducerHomeUiState(
                consumerName = "Pixel 2 XL",
                canReadLibrary = true,
                canReadLocation = true,
                sync = ProducerState.Syncing(TransferProgress(12, 40, 145_000_000, 830_000_000, "PXL_1.jpg")),
            ),
            onPair = {},
            onChooseFolders = {},
            onSyncNow = {},
            onStop = {},
            onGrantAccess = {},
            onOpenSettings = {},
        )
    }
}
