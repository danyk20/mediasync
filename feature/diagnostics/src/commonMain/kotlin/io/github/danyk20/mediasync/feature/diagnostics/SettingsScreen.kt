/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.component.LocalIsComputer
import io.github.danyk20.mediasync.core.designsystem.component.ResetDialog
import io.github.danyk20.mediasync.core.designsystem.component.deviceText
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res as DesignSystemRes
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_mediasync
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.LogSize
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.Res
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_animation_intro
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_animation_switch
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_animation_title
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_done
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_intro
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_intro_computer
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_size
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_size_hint
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_size_invalid
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_size_save
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_log_title
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_login_intro
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_login_intro_computer
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_login_intro_sender
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_login_not_installed
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_login_switch
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_login_title
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_reset_intro
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_reset_intro_computer
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_reset_title
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_title
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_check
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_checking
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_download
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_failed
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_installed
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_latest
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_newer
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_note
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.settings_updates_title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

data class SettingsUiState(
    /** The diagnostic log's size as the user types it, in MB. */
    val logSize: String = "",
    /** The size kept now, in MB. */
    val savedLogSize: String = "",
    /** [logSize] isn't a size from 0.1 to 1024 MB. */
    val logSizeInvalid: Boolean = false,
    /** A sending phone folds each file it sends into a paper airplane, while the user watches a sync they started. */
    val sendAnimation: Boolean = true,
    /** Installed from GitHub only: from Google Play, Play updates it. */
    val updates: UpdatesUi? = null,
    /** A computer only: whether MediaSync starts at login. `null` where there is no such setting. */
    val startAtLogin: StartAtLoginState? = null,
    /** This device sends (producer role): the start-at-login text says what it sends. */
    val sends: Boolean = false,
) {
    /** A valid size that isn't the one kept now. */
    val canSaveLogSize: Boolean get() = !logSizeInvalid && logSize.isNotBlank() && logSize != savedLogSize
}

/** This copy's version, and how checking GitHub for a newer one went. */
data class UpdatesUi(val installed: String, val check: UpdateCheck = UpdateCheck.Idle)

sealed interface UpdateCheck {
    data object Idle : UpdateCheck

    data object Checking : UpdateCheck

    data object Latest : UpdateCheck

    data class Newer(val release: Release) : UpdateCheck

    data object Failed : UpdateCheck
}

@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val releases: Releases,
    private val startup: LoginStartup,
) : ViewModel() {
    private val state = MutableStateFlow(
        SettingsUiState(updates = UpdatesUi(releases.installed()).takeUnless { releases.fromPlay() }),
    )
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    /** The user changed the size without saving it: a newly stored size doesn't replace what they typed. */
    private var editing = false

    init {
        viewModelScope.launch {
            settings.diagnosticsLogBytes.collect { bytes ->
                val saved = LogSize.megabytes(bytes)
                state.update {
                    if (editing) it.copy(savedLogSize = saved) else it.copy(logSize = saved, savedLogSize = saved)
                }
            }
        }
        viewModelScope.launch {
            settings.sendAnimation.collect { on -> state.update { it.copy(sendAnimation = on) } }
        }
        viewModelScope.launch { state.update { it.copy(startAtLogin = startup.state()) } }
        viewModelScope.launch {
            settings.role.collect { role -> state.update { it.copy(sends = role == DeviceRole.PRODUCER) } }
        }
    }

    fun onStartAtLoginChange(on: Boolean) {
        viewModelScope.launch { state.update { it.copy(startAtLogin = startup.set(on)) } }
    }

    fun onSendAnimationChange(on: Boolean) {
        state.update { it.copy(sendAnimation = on) }
        viewModelScope.launch { settings.setSendAnimation(on) }
    }

    fun onLogSizeChange(text: String) {
        editing = true
        state.update { it.copy(logSize = text, logSizeInvalid = LogSize.parseMegabytes(text) == null) }
    }

    /** Keeps the typed size from now on; a smaller one drops the oldest entries at once. */
    fun saveLogSize() {
        val bytes = LogSize.parseMegabytes(state.value.logSize)
        if (bytes == null) {
            state.update { it.copy(logSizeInvalid = true) }
            return
        }
        editing = false
        state.update { it.copy(logSize = LogSize.megabytes(bytes), logSizeInvalid = false) }
        viewModelScope.launch {
            settings.setDiagnosticsLogBytes(bytes)
            Diagnostics.log(Area.APP, "Diagnostic log size set to ${LogSize.megabytes(bytes)} MB")
        }
    }

    /** Asks GitHub for the latest release, only when the user taps *Check for updates*. */
    fun checkForUpdates() {
        val updates = state.value.updates?.takeIf { it.check != UpdateCheck.Checking } ?: return
        state.update { it.copy(updates = updates.copy(check = UpdateCheck.Checking)) }
        viewModelScope.launch {
            val latest = releases.latest()
            val check = when {
                latest == null -> UpdateCheck.Failed
                isNewer(latest.version, updates.installed) -> UpdateCheck.Newer(latest)
                else -> UpdateCheck.Latest
            }
            state.update { it.copy(updates = updates.copy(check = check)) }
        }
    }
}

@Composable
fun SettingsRoute(onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reset = rememberResetMediaSync()
    val links = LocalUriHandler.current
    SettingsScreen(
        state = state,
        onLogSizeChange = viewModel::onLogSizeChange,
        onSaveLogSize = viewModel::saveLogSize,
        onSendAnimation = viewModel::onSendAnimationChange,
        onStartAtLogin = viewModel::onStartAtLoginChange,
        onCheckUpdates = viewModel::checkForUpdates,
        onOpenRelease = { links.openUri(it) },
        onReset = reset,
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * *Settings* (docs/PLAN.md §3.3), from ⋮ on the home screens: how much of the diagnostic log is kept, the sending
 * animation, updates for a copy installed from GitHub, and *Reset MediaSync*, which asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLogSizeChange: (String) -> Unit,
    onSaveLogSize: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckUpdates: () -> Unit = {},
    onOpenRelease: (String) -> Unit = {},
    onSendAnimation: (Boolean) -> Unit = {},
    onStartAtLogin: (Boolean) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(Res.string.settings_done)) } },
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
            LogSizeSection(state, onLogSizeChange, onSaveLogSize)
            HorizontalDivider()
            SendAnimationSection(state.sendAnimation, onSendAnimation)
            state.startAtLogin?.let {
                HorizontalDivider()
                StartAtLoginSection(it, state.sends, onStartAtLogin)
            }
            state.updates?.let {
                HorizontalDivider()
                UpdatesSection(it, onCheckUpdates, onOpenRelease)
            }
            HorizontalDivider()
            ResetSection(onReset)
        }
    }
}

/** How much of the diagnostic log is kept: typed in MB; when full, the oldest entries make room. */
@Composable
private fun LogSizeSection(state: SettingsUiState, onChange: (String) -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_log_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(deviceText(Res.string.settings_log_intro, Res.string.settings_log_intro_computer)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.logSize,
            onValueChange = onChange,
            label = { Text(stringResource(Res.string.settings_log_size)) },
            supportingText = {
                val text = when {
                    state.logSizeInvalid -> Res.string.settings_log_size_invalid
                    else -> Res.string.settings_log_size_hint
                }
                Text(stringResource(text))
            },
            isError = state.logSizeInvalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canSaveLogSize) onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSave, enabled = state.canSaveLogSize) {
            Text(stringResource(Res.string.settings_log_size_save))
        }
    }
}

/** The sending animation (docs/PLAN.md §3.3): a paper airplane for each file a sync the user started sends. */
@Composable
private fun SendAnimationSection(on: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_animation_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        ListItem(
            // The whole row toggles, and reads out as one switch.
            modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange),
            headlineContent = { Text(stringResource(Res.string.settings_animation_switch)) },
            supportingContent = { Text(stringResource(Res.string.settings_animation_intro)) },
            trailingContent = { Switch(checked = on, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** A computer's *Start at login*: on by default once installed; a copy that is not installed says why it cannot. */
@Composable
private fun StartAtLoginSection(login: StartAtLoginState, sends: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_login_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        ListItem(
            modifier = if (login.canChange) {
                Modifier.toggleable(value = login.on, role = Role.Switch, onValueChange = onChange)
            } else {
                Modifier
            },
            headlineContent = { Text(stringResource(Res.string.settings_login_switch)) },
            supportingContent = {
                val intro = if (sends) {
                    Res.string.settings_login_intro_sender
                } else {
                    deviceText(Res.string.settings_login_intro, Res.string.settings_login_intro_computer)
                }
                val why = Res.string.settings_login_not_installed
                Text(stringResource(if (login.canChange) intro else why))
            },
            trailingContent = { Switch(checked = login.on, onCheckedChange = null, enabled = login.canChange) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** A copy installed from GitHub: its version, and a check for a newer one, only when asked (docs/PLAN.md §14.4). */
@Composable
private fun UpdatesSection(updates: UpdatesUi, onCheck: () -> Unit, onOpenRelease: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_updates_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.settings_updates_installed, updates.installed))
        when (val check = updates.check) {
            UpdateCheck.Idle -> OutlinedButton(onClick = onCheck) {
                Text(stringResource(Res.string.settings_updates_check))
            }

            UpdateCheck.Checking -> Text(stringResource(Res.string.settings_updates_checking))

            UpdateCheck.Latest -> Text(stringResource(Res.string.settings_updates_latest))

            is UpdateCheck.Newer -> {
                Text(stringResource(Res.string.settings_updates_newer, check.release.version))
                Button(onClick = { onOpenRelease(check.release.url) }) {
                    Text(stringResource(Res.string.settings_updates_download))
                }
            }

            UpdateCheck.Failed -> {
                Text(stringResource(Res.string.settings_updates_failed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onCheck) { Text(stringResource(Res.string.settings_updates_check)) }
            }
        }
        Text(
            text = stringResource(Res.string.settings_updates_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** *Reset MediaSync*: asks first, and says what it removes and what stays. */
@Composable
private fun ResetSection(onReset: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_reset_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(
                deviceText(Res.string.settings_reset_intro, Res.string.settings_reset_intro_computer),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(DesignSystemRes.string.reset_mediasync))
        }
    }
    if (confirm) {
        ResetDialog(
            onReset = {
                confirm = false
                onReset()
            },
            onCancel = { confirm = false },
        )
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(logSize = "1", savedLogSize = "1", updates = UpdatesUi("0.9.6")),
            onLogSizeChange = {},
            onSaveLogSize = {},
            onReset = {},
            onDone = {},
        )
    }
}
