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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.R as DesignR
import io.github.danyk20.mediasync.core.designsystem.component.ResetDialog
import io.github.danyk20.mediasync.core.designsystem.component.resetMediaSync
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.LogSize
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    /** The diagnostic log's size as the user types it, in MB. */
    val logSize: String = "",
    /** The size kept now, in MB. */
    val savedLogSize: String = "",
    /** [logSize] isn't a size from 0.1 to 1024 MB. */
    val logSizeInvalid: Boolean = false,
    /** Installed from GitHub only: from Google Play, Play updates it. */
    val updates: UpdatesUi? = null,
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

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: SettingsRepository, private val releases: Releases) :
    ViewModel() {
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
fun SettingsRoute(onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    SettingsScreen(
        state = state,
        onLogSizeChange = viewModel::onLogSizeChange,
        onSaveLogSize = viewModel::saveLogSize,
        onCheckUpdates = viewModel::checkForUpdates,
        onOpenRelease = { links.openUri(it) },
        onReset = { context.resetMediaSync() },
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * *Settings* (docs/PLAN.md §3.3), from ⋮ on the home screens: how much of the diagnostic log is kept, updates for a
 * copy installed from GitHub, and *Reset MediaSync*, which asks first.
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
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.settings_done)) } },
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
            text = stringResource(R.string.settings_log_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_log_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.logSize,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.settings_log_size)) },
            supportingText = {
                val text = when {
                    state.logSizeInvalid -> R.string.settings_log_size_invalid
                    else -> R.string.settings_log_size_hint
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
            Text(stringResource(R.string.settings_log_size_save))
        }
    }
}

/** A copy installed from GitHub: its version, and a check for a newer one, only when asked (docs/PLAN.md §14.4). */
@Composable
private fun UpdatesSection(updates: UpdatesUi, onCheck: () -> Unit, onOpenRelease: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_updates_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.settings_updates_installed, updates.installed))
        when (val check = updates.check) {
            UpdateCheck.Idle -> OutlinedButton(onClick = onCheck) {
                Text(stringResource(R.string.settings_updates_check))
            }

            UpdateCheck.Checking -> Text(stringResource(R.string.settings_updates_checking))

            UpdateCheck.Latest -> Text(stringResource(R.string.settings_updates_latest))

            is UpdateCheck.Newer -> {
                Text(stringResource(R.string.settings_updates_newer, check.release.version))
                Button(onClick = { onOpenRelease(check.release.url) }) {
                    Text(stringResource(R.string.settings_updates_download))
                }
            }

            UpdateCheck.Failed -> {
                Text(stringResource(R.string.settings_updates_failed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.settings_updates_check)) }
            }
        }
        Text(
            text = stringResource(R.string.settings_updates_note),
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
            text = stringResource(R.string.settings_reset_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_reset_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(DesignR.string.reset_mediasync))
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
