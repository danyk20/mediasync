/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.android.DiagnosticsReport
import io.github.danyk20.mediasync.sync.android.Fact
import io.github.danyk20.mediasync.sync.android.ProducerSync
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnosticsUiState(
    val facts: List<Fact> = emptyList(),
    /** The newest log entries, oldest first. */
    val recent: List<String> = emptyList(),
    /** On a paired sending phone only. */
    val linkTest: LinkTestUi? = null,
    /** Installed from GitHub only: from Google Play, Play updates it. */
    val updates: UpdatesUi? = null,
)

/** This copy's version, and how checking GitHub for a newer one went. */
data class UpdatesUi(val installed: String, val check: UpdateCheck = UpdateCheck.Idle)

sealed interface UpdateCheck {
    data object Idle : UpdateCheck

    data object Checking : UpdateCheck

    data object Latest : UpdateCheck

    data class Newer(val release: Release) : UpdateCheck

    data object Failed : UpdateCheck
}

/** The link test (docs/PLAN.md §7): speeds in MB/s once it ran. */
data class LinkTestUi(
    val consumerName: String,
    val running: Boolean = false,
    val oneConnection: Double? = null,
    val fourConnections: Double? = null,
    val failed: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val report: DiagnosticsReport,
    private val sync: ProducerSync,
    settings: SettingsRepository,
    peers: PeerStore,
    private val releases: Releases,
) : ViewModel() {
    private val state = MutableStateFlow(
        DiagnosticsUiState(updates = UpdatesUi(releases.installed()).takeUnless { releases.fromPlay() }),
    )
    val uiState: StateFlow<DiagnosticsUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            combine(settings.role, peers.observeConsumer()) { role, consumer ->
                consumer?.name?.takeIf { role == DeviceRole.PRODUCER }
            }.collect { name ->
                state.update {
                    it.copy(linkTest = name?.let { n -> it.linkTest?.copy(consumerName = n) ?: LinkTestUi(n) })
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val facts = report.facts()
            val recent = report.recent(RECENT)
            state.update { it.copy(facts = facts, recent = recent) }
        }
    }

    /** Measures over one connection, then over four at once: about 10 seconds. */
    fun runLinkTest() {
        val test = state.value.linkTest?.takeIf { !it.running } ?: return
        state.update { it.copy(linkTest = test.copy(running = true, failed = false)) }
        viewModelScope.launch {
            val one = sync.linkTest(streams = 1)
            val four = one?.let { sync.linkTest(streams = PARALLEL) }
            state.update {
                it.copy(
                    linkTest = test.copy(
                        running = false,
                        oneConnection = one?.megabytesPerSecond,
                        fourConnections = four?.megabytesPerSecond,
                        failed = one == null,
                    ),
                )
            }
            refresh()
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

    /** The report and the whole log, as one text. */
    suspend fun export(): String = report.export()

    fun clear() {
        viewModelScope.launch {
            report.clear()
            refresh()
        }
    }

    private companion object {
        const val RECENT = 40
        const val PARALLEL = 4
    }
}

@Composable
fun DiagnosticsRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { save(context, uri, viewModel.export()) }
    }
    DiagnosticsScreen(
        state = state,
        onTestLink = viewModel::runLinkTest,
        onShare = { scope.launch { share(context, viewModel.export()) } },
        onSave = { save.launch(FILE_NAME) },
        onClear = viewModel::clear,
        onDone = onDone,
        modifier = modifier,
        onCheckUpdates = viewModel::checkForUpdates,
        onOpenRelease = { links.openUri(it) },
    )
}

/**
 * *Diagnostics* (docs/PLAN.md §3.3): what to share when MediaSync doesn't work as expected. The log leaves the phone
 * only when the user shares or saves it (docs/PLAN.md §11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onTestLink: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckUpdates: () -> Unit = {},
    onOpenRelease: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.diagnostics_done)) } },
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
            Text(
                text = stringResource(R.string.diagnostics_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostics_share))
            }
            OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostics_save))
            }
            state.updates?.let { UpdatesSection(it, onCheckUpdates, onOpenRelease) }
            state.linkTest?.let { LinkTestSection(it, onTestLink) }
            Facts(state.facts)
            Recent(state.recent)
            TextButton(onClick = onClear) { Text(stringResource(R.string.diagnostics_clear)) }
        }
    }
}

/** A copy installed from GitHub: its version, and a check for a newer one, only when asked (docs/PLAN.md §14.4). */
@Composable
private fun UpdatesSection(updates: UpdatesUi, onCheck: () -> Unit, onOpenRelease: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.diagnostics_updates_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.diagnostics_updates_installed, updates.installed))
        when (val check = updates.check) {
            UpdateCheck.Idle -> OutlinedButton(onClick = onCheck) {
                Text(stringResource(R.string.diagnostics_updates_check))
            }

            UpdateCheck.Checking -> Text(stringResource(R.string.diagnostics_updates_checking))

            UpdateCheck.Latest -> Text(stringResource(R.string.diagnostics_updates_latest))

            is UpdateCheck.Newer -> {
                Text(stringResource(R.string.diagnostics_updates_newer, check.release.version))
                Button(onClick = { onOpenRelease(check.release.url) }) {
                    Text(stringResource(R.string.diagnostics_updates_download))
                }
            }

            UpdateCheck.Failed -> {
                Text(stringResource(R.string.diagnostics_updates_failed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.diagnostics_updates_check)) }
            }
        }
        Text(
            text = stringResource(R.string.diagnostics_updates_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkTestSection(test: LinkTestUi, onTestLink: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.diagnostics_link_test),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.diagnostics_link_test_intro, test.consumerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (test.running) {
            Text(stringResource(R.string.diagnostics_link_test_running), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedButton(onClick = onTestLink, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostics_link_test_start))
            }
        }
        if (test.failed) {
            Text(
                text = stringResource(R.string.diagnostics_link_test_failed, test.consumerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        test.oneConnection?.let { Text(stringResource(R.string.diagnostics_link_test_one, speed(it))) }
        test.fourConnections?.let { Text(stringResource(R.string.diagnostics_link_test_four, speed(it))) }
    }
}

private fun speed(megabytesPerSecond: Double) = String.format(Locale.getDefault(), "%.1f", megabytesPerSecond)

@Composable
private fun Facts(facts: List<Fact>) {
    if (facts.isEmpty()) return
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            facts.forEach { fact ->
                Column {
                    Text(
                        fact.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(fact.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Recent(entries: List<String>) {
    Text(
        text = stringResource(R.string.diagnostics_recent),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = entries.joinToString("\n").ifEmpty { stringResource(R.string.diagnostics_empty) },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shares [text] as a file through the app picker; the file sits in the cache, which only MediaSync can read. */
private suspend fun share(context: Context, text: String) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "diagnostics").apply { mkdirs() }.resolve(FILE_NAME).apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_share_title))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_share_title)))
    Diagnostics.log(Area.APP, "Diagnostics shared")
}

/** Writes [text] where the user chose to save it. */
private suspend fun save(context: Context, uri: Uri, text: String) {
    try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
        Diagnostics.log(Area.APP, "Diagnostics saved")
    } catch (e: IOException) {
        Diagnostics.log(Area.APP, "Couldn't save the diagnostics", e)
    }
}

private const val FILE_NAME = "mediasync-diagnostics.txt"

@Preview
@Composable
private fun DiagnosticsScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        DiagnosticsScreen(
            state = DiagnosticsUiState(
                facts = listOf(Fact("App", "MediaSync 0.1.0"), Fact("Role", "producer")),
                recent = listOf("2026-10-14 09:30:00.250 +0200 send     Sync done in 4.2 s: sent 3 (12.0 MB)"),
            ),
            onTestLink = {},
            onShare = {},
            onSave = {},
            onClear = {},
            onDone = {},
        )
    }
}
