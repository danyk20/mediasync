/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
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
import io.github.danyk20.mediasync.core.designsystem.component.pastMoment
import io.github.danyk20.mediasync.core.designsystem.component.relativeMoment
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.android.SyncProblem
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import io.github.danyk20.mediasync.sync.android.TurboPermissions
import io.github.danyk20.mediasync.sync.android.TurboProblem
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import java.time.Instant
import java.time.ZonedDateTime

@Composable
fun ProducerHomeRoute(
    onPair: () -> Unit,
    onChooseFolders: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseDetails: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    viewModel: ProducerHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsChanged()
    }
    val requestTurbo = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsChanged()
        viewModel.onTurboPermission(granted = it.isNotEmpty() && it.values.all { granted -> granted })
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) viewModel.dismiss(SetupPrompt.NOTIFICATIONS) // Declined: don't ask again.
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
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onDismiss = viewModel::dismiss,
        onChooseDetails = onChooseDetails,
        onInternetOverMobileData = viewModel::setInternetOverMobileData,
        onVideosOverRelay = viewModel::setVideosOverRelay,
        onOpenDiagnostics = onOpenDiagnostics,
        onStartTurbo = {
            if (state.turbo == TurboAvailability.NOT_ALLOWED) {
                requestTurbo.launch(TurboPermissions.requested.toTypedArray())
            } else {
                viewModel.startTurbo()
            }
        },
        onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
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
    onAllowNotifications: () -> Unit = {},
    onChooseDetails: () -> Unit = {},
    onInternetOverMobileData: (Boolean) -> Unit = {},
    onVideosOverRelay: (Boolean) -> Unit = {},
    onDismiss: (SetupPrompt) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onStartTurbo: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.producer_home_title)) },
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
            val consumer = state.consumerName
            when {
                consumer == null -> NotPaired(onPair)

                !state.canReadLibrary -> NeedsAccess(consumer, onGrantAccess, onOpenSettings)

                else -> {
                    Paired(consumer, state, onSyncNow, onStop)
                    Notices(state, consumer, onOpenSettings)
                    BackgroundSetupNotices(
                        askNotifications = state.askNotifications,
                        askBattery = state.askBattery,
                        onAllowNotifications = onAllowNotifications,
                        onOpenBatterySettings = onOpenSettings,
                        onDismissNotifications = { onDismiss(SetupPrompt.NOTIFICATIONS) },
                        onDismissBattery = { onDismiss(SetupPrompt.BATTERY) },
                    )
                    WithheldNotice(state, onChooseDetails)
                    HeldBackNotice(state)
                    TurboCard(state, consumer, onStartTurbo, onOpenSettings, onOpenLocationSettings)
                    SettingRow(
                        title = stringResource(R.string.producer_sources_title),
                        summary = sourcesSummary(state.sources),
                        icon = MediaSyncIcons.Folder,
                        onClick = onChooseFolders,
                    )
                    SettingRow(
                        title = stringResource(R.string.producer_details_row_title),
                        summary = stringResource(state.photoDetails.title),
                        icon = MediaSyncIcons.Info,
                        onClick = onChooseDetails,
                    )
                    InternetCard(state, consumer, onInternetOverMobileData, onVideosOverRelay)
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
            Sending(consumer, sync)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.producer_stop))
            }
        }

        ProducerState.Connecting -> StatusCard(
            title = stringResource(R.string.producer_status_connecting_title, consumer),
            body = stringResource(R.string.producer_status_connecting_body),
            icon = MediaSyncIcons.Send,
        )

        ProducerState.SettingUpTurbo -> StatusCard(
            title = stringResource(R.string.producer_turbo_setting_up_title, consumer),
            body = stringResource(R.string.producer_turbo_setting_up_body),
            icon = MediaSyncIcons.Turbo,
        )

        is ProducerState.Failed -> {
            StatusCard(
                title = stringResource(R.string.producer_status_failed_title),
                body = failureMessage(sync.reason, consumer, state.internetSync),
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
                IdleStatus(consumer, state.pendingCount, state.lastSyncMillis, state.now)
                SyncNowButton(onSyncNow)
            }
        }
    }
}

/** What needs the user's attention while nothing is being sent: a missed sync, a full receiving phone. */
@Composable
private fun Notices(state: ProducerHomeUiState, consumer: String, onOpenSettings: () -> Unit) {
    val sync = state.sync
    if (sync !is ProducerState.Idle && sync !is ProducerState.Failed) return
    (sync as? ProducerState.Idle)?.turboProblem?.let { problem ->
        NoticeCard(
            title = stringResource(R.string.producer_turbo_problem_title),
            body = stringResource(
                when (problem) {
                    TurboProblem.RECEIVER_UNSUPPORTED -> R.string.producer_turbo_problem_unsupported
                    TurboProblem.RECEIVER_NOT_ALLOWED -> R.string.producer_turbo_problem_not_allowed
                    TurboProblem.NO_LINK -> R.string.producer_turbo_problem_no_link
                    TurboProblem.DROPPED -> R.string.producer_turbo_problem_dropped
                },
                consumer,
            ),
            icon = MediaSyncIcons.Turbo,
            warning = problem != TurboProblem.RECEIVER_UNSUPPORTED,
        )
    }
    state.missedSync?.let { missed -> MissedSyncNotice(missed, consumer, state.now ?: missed.due, onOpenSettings) }
    if (state.waitingForSpace > 0) {
        NoticeCard(
            title = stringResource(R.string.producer_waiting_for_space_title, consumer),
            body = pluralStringResource(
                R.plurals.producer_waiting_for_space_body,
                state.waitingForSpace,
                state.waitingForSpace,
                consumer,
            ),
            icon = MediaSyncIcons.Warning,
            warning = true,
        )
    }
}

/** "The sync planned for today at 3:00 AM didn't run", and why, as far as known (docs/PLAN.md §10.3). */
@Composable
private fun MissedSyncNotice(missed: MissedSync, consumer: String, now: ZonedDateTime, onOpenSettings: () -> Unit) {
    val restricted = missed.problem == null && missed.batteryRestricted
    NoticeCard(
        title = stringResource(R.string.producer_missed_title, relativeMoment(missed.due, now)),
        body = when (missed.problem) {
            SyncProblem.NO_WIFI -> stringResource(R.string.producer_missed_no_wifi)

            SyncProblem.UNREACHABLE -> stringResource(R.string.producer_missed_unreachable, consumer)

            SyncProblem.CONNECTION_LOST -> stringResource(R.string.producer_missed_connection_lost, consumer)

            SyncProblem.HOT -> stringResource(R.string.producer_missed_hot)

            SyncProblem.LOW_BATTERY -> stringResource(R.string.producer_missed_low_battery)

            null -> stringResource(
                if (restricted) R.string.producer_missed_battery_restricted else R.string.producer_missed_unknown,
            )
        },
        icon = MediaSyncIcons.Warning,
        action = if (restricted) stringResource(R.string.producer_access_settings) else null,
        onAction = onOpenSettings,
        warning = true,
    )
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

/** A setting of what is sent (folders, details), and the way to change it. */
@Composable
private fun SettingRow(title: String, summary: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(painterResource(icon), contentDescription = null) },
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

/**
 * Internet sync (docs/PLAN.md §8.4), as this phone uses it: over Wi-Fi networks, and over mobile data only if the user
 * allows it; through the relay, videos wait unless the user lets them go (§17, relay saver). Shown once the receiving
 * phone has set it up.
 */
@Composable
private fun InternetCard(
    state: ProducerHomeUiState,
    consumer: String,
    onMobileData: (Boolean) -> Unit,
    onRelayVideos: (Boolean) -> Unit,
) {
    if (!state.internetSync) return
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = stringResource(R.string.producer_mobile_data_title),
            body = stringResource(R.string.producer_mobile_data_body, consumer),
            icon = MediaSyncIcons.MobileData,
            checked = state.internetOverMobileData,
            onChange = onMobileData,
        )
        SwitchRow(
            title = stringResource(R.string.producer_relay_videos_title),
            body = stringResource(R.string.producer_relay_videos_body),
            icon = MediaSyncIcons.Send,
            checked = state.videosOverRelay,
            onChange = onRelayVideos,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** Videos the relay saver kept for Wi-Fi or a direct connection in the last sync (docs/PLAN.md §17). */
@Composable
private fun HeldBackNotice(state: ProducerHomeUiState) {
    val held = (state.sync as? ProducerState.Idle)?.lastResult?.heldBack ?: 0
    if (held == 0) return
    NoticeCard(
        title = pluralStringResource(R.plurals.producer_held_back_title, held, held),
        body = stringResource(R.string.producer_held_back_body),
        icon = MediaSyncIcons.Info,
    )
}

/** Files the *Photo details* setting holds back, and the way to change it (docs/PLAN.md §6.4). */
@Composable
private fun WithheldNotice(state: ProducerHomeUiState, onChooseDetails: () -> Unit) {
    if (state.withheldCount == 0 || state.sync is ProducerState.Syncing) return
    NoticeCard(
        title = pluralStringResource(R.plurals.producer_withheld_title, state.withheldCount, state.withheldCount),
        body = stringResource(R.string.producer_withheld_body),
        icon = MediaSyncIcons.Info,
        action = stringResource(R.string.producer_details_row_title),
        onAction = onChooseDetails,
    )
}

/**
 * Turbo transfer (docs/PLAN.md §8.3), while there's something to send and this phone has Wi-Fi Direct. Suggested
 * when more than 2 GB are waiting; says what it needs first: location on, or permission from the app's settings.
 */
@Composable
private fun TurboCard(
    state: ProducerHomeUiState,
    consumer: String,
    onStartTurbo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val idle = state.sync is ProducerState.Idle || state.sync is ProducerState.Failed
    if (!idle || (state.pendingCount ?: 0) == 0 || state.turbo == TurboAvailability.UNSUPPORTED) return
    val body = buildList {
        add(stringResource(R.string.producer_turbo_body, consumer))
        if (state.turboBatteryLow) add(stringResource(R.string.producer_turbo_battery_low))
        if (state.turbo == TurboAvailability.LOCATION_OFF) add(stringResource(R.string.producer_turbo_location_off))
        if (state.turboDeclined) add(stringResource(R.string.producer_turbo_declined))
    }.joinToString("\n\n")
    val (action, onAction) = when {
        state.turbo == TurboAvailability.LOCATION_OFF ->
            stringResource(R.string.producer_turbo_location_settings) to onOpenLocationSettings

        state.turboDeclined -> stringResource(R.string.producer_access_settings) to onOpenSettings

        else -> stringResource(R.string.producer_turbo_start) to onStartTurbo
    }
    NoticeCard(
        title = if (state.pendingBytes > TURBO_SUGGESTED_BYTES) {
            stringResource(R.string.producer_turbo_suggestion_title, formatBytes(state.pendingBytes))
        } else {
            stringResource(R.string.producer_turbo_title)
        },
        body = body,
        icon = MediaSyncIcons.Turbo,
        action = action,
        onAction = onAction,
    )
}

/** More than this waiting, Turbo is worth suggesting: a first sync, or after a holiday. */
private const val TURBO_SUGGESTED_BYTES = 2_000_000_000L

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
private fun Sending(consumer: String, sync: ProducerState.Syncing) {
    val progress = sync.progress
    val turbo = sync.turbo
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(
            title = stringResource(
                when {
                    turbo -> R.string.producer_turbo_sending_title
                    sync.internet -> R.string.producer_internet_sending_title
                    else -> R.string.producer_status_sending_title
                },
                consumer,
            ),
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
            icon = if (turbo) MediaSyncIcons.Turbo else MediaSyncIcons.Send,
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
private fun IdleStatus(consumer: String, pending: Int?, lastSyncMillis: Long?, now: ZonedDateTime?) {
    val lastSync = if (lastSyncMillis != null && now != null) {
        val at = Instant.ofEpochMilli(lastSyncMillis).atZone(now.zone)
        stringResource(R.string.producer_last_sync, pastMoment(at, now))
    } else {
        null
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
private fun failureMessage(reason: SyncFailure, consumer: String, internetSync: Boolean): String = when (reason) {
    SyncFailure.NOT_PAIRED -> stringResource(R.string.producer_status_not_paired_body)

    // With internet sync, the same Wi-Fi isn't needed: the other phone must be listening.
    SyncFailure.UNREACHABLE -> stringResource(
        if (internetSync) R.string.producer_failure_unreachable_internet else R.string.producer_failure_unreachable,
        consumer,
    )

    SyncFailure.CONNECTION_LOST -> stringResource(R.string.producer_failure_connection_lost, consumer)

    SyncFailure.NOT_ALLOWED -> stringResource(R.string.producer_failure_not_allowed, consumer)

    SyncFailure.INCOMPATIBLE -> stringResource(R.string.producer_failure_incompatible, consumer)

    SyncFailure.TURBO_UNREACHABLE -> stringResource(R.string.producer_failure_turbo_unreachable, consumer)

    SyncFailure.TURBO_NOT_SET_UP -> stringResource(R.string.producer_failure_turbo_not_set_up, consumer)
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
