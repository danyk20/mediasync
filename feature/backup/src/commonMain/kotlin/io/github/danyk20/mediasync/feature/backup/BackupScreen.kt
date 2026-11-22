/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.danyk20.mediasync.connector.CleanupShortcut
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.DeviceAppProfile
import io.github.danyk20.mediasync.connector.SetupStep
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.feature.backup.generated.resources.Res
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_done
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_change
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_choose
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_copied
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_intro
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_none
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_stop
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_title
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_today
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_unreachable
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_folder_waiting
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_free_up_app
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_free_up_open
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_free_up_smart_storage
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_free_up_storage_settings
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_free_up_title
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_install
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_intro
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_never_deletes
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_none
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_not_installed
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_open_app
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_set_up_done
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_setup_body
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_setup_title
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_status_handed_over
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_status_note
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_status_removed
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_status_title
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_status_today
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_status_waiting
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_step_folder
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_step_storage_saver
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_steps_heading
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_stop
import io.github.danyk20.mediasync.feature.backup.generated.resources.backup_title
import java.text.NumberFormat
import org.jetbrains.compose.resources.stringResource

@Composable
fun BackupRoute(onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: BackupViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val system = rememberBackupSystemActions()
    // Back from installing or setting up the app.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    BackupScreen(
        state = state,
        actions = BackupActions(
            openApp = { system.openApp(it.packageName) },
            install = { system.openStoreListing(it.packageName) },
            openStorageSettings = { system.openStorageSettings() },
            connect = viewModel::connect,
            disconnect = viewModel::disconnect,
            chooseFolder = viewModel::chooseFolder,
            stopFolder = viewModel::stopFolder,
        ),
        onDone = onDone,
        modifier = modifier,
    )
}

/** Cloud backup on the receiving phone (docs/PLAN.md §9.3.1). [state] `null`: still loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(state: BackupUiState?, actions: BackupActions, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.backup_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(Res.string.backup_done)) } },
            )
        },
    ) { padding ->
        if (state == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val intro = if (state.folder != null) Res.string.backup_folder_intro else Res.string.backup_intro
            Text(stringResource(intro), style = MaterialTheme.typography.bodyLarge)
            state.folder?.let { FolderStatus(it, actions) }
            if (state.apps.isEmpty() && state.folder == null) Text(stringResource(Res.string.backup_none))
            state.apps.forEach { app ->
                if (app.overview == null) AppSetup(app, actions) else AppStatus(app, actions)
            }
        }
    }
}

/** A computer's backup folder: where it is, whether it can be reached, and how much has been copied. */
@Composable
private fun FolderStatus(folder: FolderBackup, actions: BackupActions) {
    val count = NumberFormat.getIntegerInstance()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.backup_folder_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (folder.path == null) {
                Text(stringResource(Res.string.backup_folder_none))
            } else {
                Text(folder.path)
                if (!folder.reachable) {
                    Text(stringResource(Res.string.backup_folder_unreachable), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(Res.string.backup_folder_today, count.format(folder.today)))
                Text(stringResource(Res.string.backup_folder_copied, count.format(folder.copied)))
                if (folder.waiting > 0) {
                    Text(stringResource(Res.string.backup_folder_waiting, count.format(folder.waiting)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val choose = if (folder.path ==
                    null
                ) {
                    Res.string.backup_folder_choose
                } else {
                    Res.string.backup_folder_change
                }
                Button(onClick = actions.chooseFolder) { Text(stringResource(choose)) }
                if (folder.path != null) {
                    TextButton(onClick = actions.stopFolder) { Text(stringResource(Res.string.backup_folder_stop)) }
                }
            }
        }
    }
}

/** How to set the app up, step by step, before MediaSync hands it anything. */
@Composable
private fun AppSetup(app: AppBackup, actions: BackupActions) {
    val profile = app.profile
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.backup_setup_title, profile.appName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(Res.string.backup_setup_body, profile.appName),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!app.installed) {
                Text(
                    stringResource(Res.string.backup_not_installed, profile.appName),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { actions.install(profile) }) {
                    Text(stringResource(Res.string.backup_install, profile.appName))
                }
                return@Column
            }
            Text(
                stringResource(Res.string.backup_steps_heading, profile.appName),
                style = MaterialTheme.typography.titleSmall,
            )
            profile.setup.forEachIndexed { index, step ->
                Text("${index + 1}. ${stepText(step, profile)}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.openApp(profile) }) {
                    Text(stringResource(Res.string.backup_open_app, profile.appName))
                }
                Button(onClick = {
                    actions.connect(app.connector)
                }) { Text(stringResource(Res.string.backup_set_up_done)) }
            }
        }
    }
}

@Composable
private fun stepText(step: SetupStep, profile: DeviceAppProfile): String = when (step) {
    SetupStep.BACK_UP_FOLDER -> stringResource(Res.string.backup_step_folder, profile.folder.substringAfterLast('/'))
    SetupStep.STORAGE_SAVER -> stringResource(Res.string.backup_step_storage_saver)
}

/** How the app's backups stand, and where to free up space once it has backed things up. */
@Composable
private fun AppStatus(app: AppBackup, actions: BackupActions) {
    val profile = app.profile
    val overview = app.overview ?: return
    val count = NumberFormat.getIntegerInstance()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.backup_status_title, profile.appName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.backup_status_today, count.format(overview.today)))
            Text(
                stringResource(
                    Res.string.backup_status_handed_over,
                    count.format(overview.handedOver),
                    profile.appName,
                ),
            )
            if (overview.removedByApp > 0) {
                Text(stringResource(Res.string.backup_status_removed, count.format(overview.removedByApp)))
            }
            if (overview.waiting >
                0
            ) {
                Text(stringResource(Res.string.backup_status_waiting, count.format(overview.waiting)))
            }
            Text(
                stringResource(Res.string.backup_status_note, profile.appName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            FreeUpSpace(profile, actions)
            TextButton(onClick = { actions.disconnect(app) }) { Text(stringResource(Res.string.backup_stop)) }
        }
    }
}

/** The other app's own cleanup, and the phone's: MediaSync never deletes these files itself (docs/PLAN.md §9.3.1). */
@Composable
private fun FreeUpSpace(profile: DeviceAppProfile, actions: BackupActions) {
    Text(
        stringResource(Res.string.backup_free_up_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics {
            heading()
        },
    )
    if (CleanupShortcut.APP_FREE_UP_SPACE in profile.cleanup) {
        Text(
            stringResource(Res.string.backup_free_up_app, profile.appName),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { actions.openApp(profile) }) {
            Text(stringResource(Res.string.backup_free_up_open, profile.appName))
        }
    }
    if (CleanupShortcut.SYSTEM_SMART_STORAGE in profile.cleanup) {
        Text(stringResource(Res.string.backup_free_up_smart_storage), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = actions.openStorageSettings) {
            Text(stringResource(Res.string.backup_free_up_storage_settings))
        }
    }
    Text(
        stringResource(Res.string.backup_never_deletes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview
@Composable
private fun BackupScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        BackupScreen(
            state = BackupUiState(
                listOf(
                    AppBackup(
                        connector = ConnectorId("google-photos-app"),
                        profile = DeviceAppProfile(
                            packageName = "com.google.android.apps.photos",
                            appName = "Google Photos",
                            folder = "DCIM/MediaSync",
                            layout = io.github.danyk20.mediasync.connector.FolderLayout.SINGLE_FOLDER,
                            setup = listOf(SetupStep.BACK_UP_FOLDER, SetupStep.STORAGE_SAVER),
                            cleanup = listOf(CleanupShortcut.APP_FREE_UP_SPACE, CleanupShortcut.SYSTEM_SMART_STORAGE),
                        ),
                        installed = true,
                    ),
                ),
            ),
            actions = BackupActions(),
            onDone = {},
        )
    }
}
