/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.designsystem.component.BackgroundSetupNotices
import io.github.danyk20.mediasync.core.designsystem.component.MoreMenu
import io.github.danyk20.mediasync.core.designsystem.component.NoticeCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.component.formatBytes
import io.github.danyk20.mediasync.core.designsystem.component.scheduleSummary
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.RelayLevel
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import io.github.danyk20.mediasync.sync.android.TurboPermissions
import io.github.danyk20.mediasync.sync.android.level
import java.text.NumberFormat

@Composable
fun ConsumerHomeRoute(
    onAddProducer: () -> Unit,
    onChangeSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    onSetUpInternet: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    viewModel: ConsumerHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) viewModel.dismiss(SetupPrompt.NOTIFICATIONS) // Declined: don't ask again.
        viewModel.refresh()
    }
    val requestTurbo = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onTurboAnswered()
    }
    val requestTurboReceive = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.refresh()
        if (granted.isNotEmpty() && granted.values.all { it }) viewModel.startTurboReceive()
    }
    // Back from settings, or space freed up meanwhile.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    ConsumerHomeScreen(
        state = state,
        onAddProducer = onAddProducer,
        onChangeSchedule = onChangeSchedule,
        modifier = modifier,
        onSetUpInternet = onSetUpInternet,
        onOpenBackup = onOpenBackup,
        onRemoveProducer = { viewModel.removeProducer(it.id) },
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenSettings = { context.openAppSettings() },
        onOpenStorage = { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) },
        onDismiss = viewModel::dismiss,
        onOpenDiagnostics = onOpenDiagnostics,
        onAllowTurbo = {
            if (state.turbo == TurboAvailability.LOCATION_OFF) {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                viewModel.onTurboAnswered()
            } else {
                requestTurbo.launch(TurboPermissions.requested.toTypedArray())
            }
        },
        onDismissTurbo = viewModel::onTurboAnswered,
        onStartTurboReceive = {
            when (state.turbo) {
                TurboAvailability.NOT_ALLOWED -> requestTurboReceive.launch(TurboPermissions.requested.toTypedArray())

                TurboAvailability.LOCATION_OFF -> context.startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                )

                else -> viewModel.startTurboReceive()
            }
        },
        onStopTurboReceive = viewModel::stopTurboReceive,
    )
}

/** MediaSync's page in the system settings: permissions, battery use. */
private fun Context.openAppSettings() = startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
)

/** Home screen of the phone that receives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumerHomeScreen(
    state: ConsumerHomeUiState,
    onAddProducer: () -> Unit,
    onChangeSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    onSetUpInternet: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onRemoveProducer: (PairedPhone) -> Unit = {},
    onAllowNotifications: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onDismiss: (SetupPrompt) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onAllowTurbo: () -> Unit = {},
    onDismissTurbo: () -> Unit = {},
    onStartTurboReceive: () -> Unit = {},
    onStopTurboReceive: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.consumer_home_title)) },
                actions = { MoreMenu(onOpenDiagnostics) },
            )
        },
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
            if (state.turboReceiving && state.transfer == null) {
                OutlinedButton(onClick = onStopTurboReceive, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.consumer_turbo_receive_stop))
                }
            }
            Button(onClick = onAddProducer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.consumer_add_producer))
            }
            StorageNotice(state, onOpenStorage)
            TurboNotice(state, onAllowTurbo, onDismissTurbo)
            BackgroundSetupNotices(
                askNotifications = state.askNotifications,
                askBattery = state.askBattery,
                onAllowNotifications = onAllowNotifications,
                onOpenBatterySettings = onOpenSettings,
                onDismissNotifications = { onDismiss(SetupPrompt.NOTIFICATIONS) },
                onDismissBattery = { onDismiss(SetupPrompt.BATTERY) },
            )
            ScheduleRow(state.schedule, onChangeSchedule)
            InternetRow(state.internetSync, state.relay, onSetUpInternet)
            state.backup?.let { BackupRow(it, onOpenBackup) }
            TurboReceiveRow(state, onStartTurboReceive)
            if (state.producers.isNotEmpty()) Producers(state.producers, onRemoveProducer)
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

/** A sending phone asked for Turbo transfer (docs/PLAN.md §8.3), which needs something allowed here first. */
@Composable
private fun TurboNotice(state: ConsumerHomeUiState, onAllowTurbo: () -> Unit, onDismissTurbo: () -> Unit) {
    if (!state.askTurbo) return
    val locationOff = state.turbo == TurboAvailability.LOCATION_OFF
    NoticeCard(
        title = stringResource(R.string.consumer_turbo_title),
        body = stringResource(if (locationOff) R.string.consumer_turbo_location_off else R.string.consumer_turbo_body),
        icon = MediaSyncIcons.Turbo,
        action = stringResource(
            if (locationOff) R.string.consumer_turbo_location_settings else R.string.consumer_turbo_allow,
        ),
        onAction = onAllowTurbo,
        onDismiss = onDismissTurbo,
    )
}

/**
 * *Turbo receive* (docs/PLAN.md §8.3): for a sending phone that isn't on this Wi-Fi. Offered while there are sending
 * phones, nothing is coming in, and this phone has Wi-Fi Direct.
 */
@Composable
private fun TurboReceiveRow(state: ConsumerHomeUiState, onStart: () -> Unit) {
    val offered = state.producers.isNotEmpty() && state.transfer == null && !state.directLinkOpen &&
        !state.askTurbo && state.turbo != TurboAvailability.UNSUPPORTED
    if (!offered) return
    val locationOff = state.turbo == TurboAvailability.LOCATION_OFF
    OutlinedCard(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.consumer_turbo_receive_title)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (locationOff) R.string.consumer_turbo_location_off else R.string.consumer_turbo_receive_body,
                    ),
                )
            },
            leadingContent = { Icon(painterResource(MediaSyncIcons.Turbo), contentDescription = null) },
            trailingContent = {
                Text(
                    text = stringResource(
                        if (locationOff) {
                            R.string.consumer_turbo_location_settings
                        } else {
                            R.string.consumer_turbo_receive_start
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun Status(state: ConsumerHomeUiState) {
    val transfer = state.transfer
    when {
        transfer != null -> Receiving(transfer, overTurbo = state.directLinkOpen)

        state.turboReceiving -> StatusCard(
            title = stringResource(R.string.consumer_turbo_receive_waiting_title),
            body = stringResource(R.string.consumer_turbo_receive_waiting_body),
            icon = MediaSyncIcons.Turbo,
            tone = StatusTone.Positive,
        )

        state.directLinkOpen -> StatusCard(
            title = stringResource(R.string.consumer_turbo_open_title),
            body = stringResource(R.string.consumer_turbo_open_body),
            icon = MediaSyncIcons.Turbo,
        )

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

/** Who is sending, and how far it got; [overTurbo] while a direct link is open. */
@Composable
private fun Receiving(transfer: IncomingTransfer, overTurbo: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(
            title = stringResource(
                if (overTurbo) R.string.consumer_turbo_receiving_title else R.string.consumer_status_receiving_title,
                transfer.producerName,
            ),
            body = stringResource(
                R.string.consumer_status_receiving_body,
                transfer.itemsDone,
                transfer.itemsTotal,
                formatBytes(transfer.bytesDone),
                formatBytes(transfer.bytesTotal),
            ),
            icon = if (overTurbo) MediaSyncIcons.Turbo else MediaSyncIcons.Receive,
            tone = StatusTone.Positive,
        )
        LinearProgressIndicator(
            progress = {
                if (transfer.bytesTotal == 0L) 0f else transfer.bytesDone.toFloat() / transfer.bytesTotal
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The storage guard (docs/PLAN.md §2.2): receiving stops when this phone runs out of space. */
@Composable
private fun StorageNotice(state: ConsumerHomeUiState, onOpenStorage: () -> Unit) {
    val waiting = state.waitingForSpace
    when {
        state.storageFull -> NoticeCard(
            title = stringResource(R.string.consumer_storage_full_title),
            body = stringResource(R.string.consumer_storage_full_body) +
                if (waiting > 0) " " + pluralStringResource(R.plurals.consumer_waiting, waiting, waiting) else "",
            icon = MediaSyncIcons.Warning,
            action = stringResource(R.string.consumer_storage_open),
            onAction = onOpenStorage,
            warning = true,
        )

        waiting > 0 -> NoticeCard(
            title = stringResource(R.string.consumer_storage_didnt_fit_title),
            body = pluralStringResource(R.plurals.consumer_storage_didnt_fit_body, waiting, waiting),
            icon = MediaSyncIcons.Warning,
            action = stringResource(R.string.consumer_storage_open),
            onAction = onOpenStorage,
        )
    }
}

/** When the sending phones sync, and the way to change it. */
@Composable
private fun ScheduleRow(schedule: SyncSchedule, onChangeSchedule: () -> Unit) {
    OutlinedCard(onClick = onChangeSchedule, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.consumer_schedule_row_title)) },
            supportingContent = { Text(scheduleSummary(schedule)) },
            trailingContent = {
                Text(
                    text = stringResource(R.string.consumer_schedule_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Cloud backup (docs/PLAN.md §9.3.1): whether, and by what, what's received is backed up. */
@Composable
private fun BackupRow(backup: BackupLine, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.consumer_backup_row_title)) },
            supportingContent = {
                Text(
                    when (backup) {
                        BackupLine.Off -> stringResource(R.string.consumer_backup_row_off)

                        is BackupLine.On -> stringResource(
                            R.string.consumer_backup_row_on,
                            backup.app,
                            NumberFormat.getIntegerInstance().format(backup.today),
                        )
                    },
                )
            },
            trailingContent = {
                val action = if (backup is BackupLine.On) {
                    R.string.consumer_backup_row_open
                } else {
                    R.string.consumer_internet_set_up
                }
                Text(
                    text = stringResource(action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Internet sync (docs/PLAN.md §8.4): whether sending phones away from this Wi-Fi can sync. */
@Composable
private fun InternetRow(on: Boolean, relay: RelayUsage?, onSetUp: () -> Unit) {
    OutlinedCard(onClick = onSetUp, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.consumer_internet_row_title)) },
            supportingContent = {
                Column {
                    Text(
                        stringResource(
                            if (on) R.string.consumer_internet_row_on else R.string.consumer_internet_row_off,
                        ),
                    )
                    // The one-line meter (docs/PLAN.md §8.5): accented from 80 %, red once the relay stopped.
                    relay?.let {
                        Text(
                            text = stringResource(
                                R.string.consumer_internet_row_relay,
                                gigabytes(it.usedBytes),
                                gigabytes(it.freeBytes),
                            ),
                            color = when (it.level) {
                                RelayLevel.FINE -> MaterialTheme.colorScheme.onSurfaceVariant
                                RelayLevel.HIGH, RelayLevel.NEARLY_USED_UP -> MaterialTheme.colorScheme.tertiary
                                RelayLevel.USED_UP -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            },
            trailingContent = {
                Text(
                    text = stringResource(
                        if (on) R.string.consumer_schedule_change else R.string.consumer_internet_set_up,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun Producers(phones: List<PairedPhone>, onRemove: (PairedPhone) -> Unit) {
    var removing by remember { mutableStateOf<PairedPhone?>(null) }
    Column {
        Text(
            text = stringResource(R.string.consumer_producers_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { heading() },
        )
        phones.forEachIndexed { index, phone ->
            if (index > 0) HorizontalDivider()
            // TalkBack says which phone: "Remove Anna's Pixel".
            val described = stringResource(R.string.consumer_producer_remove_described, phone.name)
            ListItem(
                headlineContent = { Text(phone.name) },
                trailingContent = {
                    TextButton(
                        onClick = { removing = phone },
                        modifier = Modifier.semantics { contentDescription = described },
                    ) { Text(stringResource(R.string.consumer_producer_remove)) }
                },
            )
        }
    }
    removing?.let { phone ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.consumer_producer_remove_title, phone.name)) },
            text = { Text(stringResource(R.string.consumer_producer_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(phone)
                }) { Text(stringResource(R.string.consumer_producer_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.consumer_cancel)) }
            },
        )
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
                producers = listOf(PairedPhone(DeviceId.of(ByteArray(32) { 1 }), "Anna's Pixel")),
                receivedCount = 1234,
            ),
            onAddProducer = {},
            onChangeSchedule = {},
        )
    }
}
