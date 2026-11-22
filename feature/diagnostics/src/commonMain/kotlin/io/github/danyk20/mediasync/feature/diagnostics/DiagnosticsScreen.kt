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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.TestFileSize
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.Res
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_clear
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_done
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_empty
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_intro
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test_failed
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test_four
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test_intro
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test_one
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test_running
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_link_test_start
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_recent
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_save
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_share
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_title
import io.github.danyk20.mediasync.sync.android.Fact
import io.github.danyk20.mediasync.sync.android.ProducerSync
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

data class DiagnosticsUiState(
    val facts: List<Fact> = emptyList(),
    /** The newest log entries, oldest first. */
    val recent: List<String> = emptyList(),
    /** On a paired sending phone only. */
    val linkTest: LinkTestUi? = null,
    /** On a paired sending phone, and on any phone that checked one. */
    val testFile: TestFileUi? = null,
)

/** The link test (docs/PLAN.md §7): speeds in MB/s once it ran. */
data class LinkTestUi(
    val consumerName: String,
    val running: Boolean = false,
    val oneConnection: Double? = null,
    val fourConnections: Double? = null,
    val failed: Boolean = false,
)

@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class DiagnosticsViewModel(
    private val device: DiagnosticsDevice,
    private val sync: ProducerSync,
    settings: SettingsRepository,
    peers: PeerStore,
) : ViewModel() {
    private val state = MutableStateFlow(DiagnosticsUiState())
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
        viewModelScope.launch {
            combine(settings.role, peers.observeConsumer(), device.testFile) { role, consumer, test ->
                consumer?.name?.takeIf { role == DeviceRole.PRODUCER } to test
            }.collect { (consumer, test) ->
                state.update {
                    val shown = consumer != null || test.last != null
                    it.copy(
                        testFile = (it.testFile ?: TestFileUi()).copy(
                            consumerName = consumer,
                            waitingBytes = test.waiting?.size,
                            problem = test.problem,
                            last = test.last,
                        ).takeIf { shown },
                    )
                }
            }
        }
    }

    fun onTestFileSizeChange(text: String) = state.update {
        it.copy(testFile = it.testFile?.copy(size = text, sizeInvalid = TestFileSize.parseMegabytes(text) == null))
    }

    /** Adds a test file of the size typed, to send first in the next sync. */
    fun addTestFile() {
        val test = state.value.testFile?.takeIf { it.consumerName != null } ?: return
        val bytes = TestFileSize.parseMegabytes(test.size)
        if (bytes == null) {
            state.update { it.copy(testFile = test.copy(sizeInvalid = true)) }
            return
        }
        device.addTestFile(bytes)
        refresh()
    }

    /** Removes the test file waiting, before it's sent. */
    fun removeTestFile() {
        device.removeTestFile()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val facts = device.facts()
            val recent = device.recent(RECENT)
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

    /** The report and the whole log, as one text. */
    suspend fun export(): String = device.export()

    fun clear() {
        viewModelScope.launch {
            device.clear()
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
    viewModel: DiagnosticsViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val share = rememberLogSharer()
    val save = rememberLogSaver { viewModel.export() }
    DiagnosticsScreen(
        state = state,
        onTestLink = viewModel::runLinkTest,
        testFileActions = TestFileActions(
            onSizeChange = viewModel::onTestFileSizeChange,
            onAdd = viewModel::addTestFile,
            onRemove = viewModel::removeTestFile,
        ),
        onShare = { scope.launch { share(viewModel.export()) } },
        onSave = save,
        onClear = viewModel::clear,
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * *Diagnostics* (docs/PLAN.md §3.3): what to share when MediaSync doesn't work as expected. The log leaves the phone
 * only when the user shares or saves it (docs/PLAN.md §11). A sending phone also tests the link and sends test files
 * (docs/PLAN.md §7).
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
    testFileActions: TestFileActions = TestFileActions(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.diagnostics_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(Res.string.diagnostics_done)) } },
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
                text = stringResource(Res.string.diagnostics_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.diagnostics_share))
            }
            OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.diagnostics_save))
            }
            state.linkTest?.let { LinkTestSection(it, onTestLink) }
            state.testFile?.let { TestFileSection(it, testFileActions) }
            Facts(state.facts)
            Recent(state.recent)
            TextButton(onClick = onClear) { Text(stringResource(Res.string.diagnostics_clear)) }
        }
    }
}

@Composable
private fun LinkTestSection(test: LinkTestUi, onTestLink: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.diagnostics_link_test),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Res.string.diagnostics_link_test_intro, test.consumerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (test.running) {
            Text(stringResource(Res.string.diagnostics_link_test_running), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedButton(onClick = onTestLink, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.diagnostics_link_test_start))
            }
        }
        if (test.failed) {
            Text(
                text = stringResource(Res.string.diagnostics_link_test_failed, test.consumerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        test.oneConnection?.let { Text(stringResource(Res.string.diagnostics_link_test_one, speed(it))) }
        test.fourConnections?.let { Text(stringResource(Res.string.diagnostics_link_test_four, speed(it))) }
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
        text = stringResource(Res.string.diagnostics_recent),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = entries.joinToString("\n").ifEmpty { stringResource(Res.string.diagnostics_empty) },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
