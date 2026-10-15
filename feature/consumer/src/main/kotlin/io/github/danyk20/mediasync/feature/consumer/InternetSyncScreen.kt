/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the internet sync screen shows. */
data class InternetSyncUiState(
    /** The receiving phone's rendezvous Worker; `null` while internet sync is off. */
    val worker: String? = null,
    /** Paired phones can reach this phone through the Worker now. */
    val reachable: Boolean = false,
)

/**
 * Internet sync (docs/PLAN.md §8.4), as the receiving phone sets it up: the rendezvous Worker its sending phones
 * find it through when they aren't on its Wi-Fi. Each household runs its own, in its own Cloudflare account.
 */
@HiltViewModel
class InternetSyncViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val receiver: ConsumerReceiver,
) : ViewModel() {

    /** `null` while loading. */
    val uiState: StateFlow<InternetSyncUiState?> = combine(settings.rendezvousUrl, receiver.state) { worker, state ->
        InternetSyncUiState(worker, reachable = worker != null && state.overInternet)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Saves the Worker at [address]; `false` (and nothing saved) if it isn't an `https` address. */
    fun save(address: String): Boolean {
        val worker = workerAddress(address) ?: return false
        update(worker)
        return true
    }

    fun turnOff() = update(null)

    private fun update(worker: String?) {
        viewModelScope.launch {
            settings.setRendezvousUrl(worker)
            receiver.refreshInternet()
        }
    }
}

/**
 * [text] as a Worker's address: `https://host[:port]`, without a path; `https://` is added when there's no scheme.
 * `null` if it isn't one. Sending phones only accept `https` (PLAN §8.4): the address travels in the sync session.
 */
internal fun workerAddress(text: String): String? {
    val trimmed = text.trim().trimEnd('/')
    val uri = parse(if ("://" in trimmed) trimmed else "https://$trimmed")
        ?.takeIf { it.scheme.equals("https", ignoreCase = true) && it.isPlainHost() } ?: return null
    val host = uri.host?.lowercase()?.takeIf { '.' in it } ?: return null
    return if (uri.port == -1) "https://$host" else "https://$host:${uri.port}"
}

private fun parse(text: String): URI? = try {
    URI(text)
} catch (_: URISyntaxException) {
    null
}

/** Only a host (and port): no path, query, fragment or user. */
private fun URI.isPlainHost() = rawPath.isNullOrEmpty() && rawQuery == null && rawFragment == null && userInfo == null

@Composable
fun InternetSyncRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InternetSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InternetSyncScreen(
        state = state,
        onSave = viewModel::save,
        onTurnOff = viewModel::turnOff,
        onDone = onDone,
        modifier = modifier,
    )
}

/** The receiving phone's internet sync setting. [state] `null`: still loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternetSyncScreen(
    state: InternetSyncUiState?,
    onSave: (String) -> Boolean,
    onTurnOff: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.consumer_internet_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.consumer_schedule_done)) } },
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
            Text(stringResource(R.string.consumer_internet_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.consumer_internet_costs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    state.worker == null -> stringResource(R.string.consumer_internet_status_off)
                    state.reachable -> stringResource(R.string.consumer_internet_status_reachable)
                    else -> stringResource(R.string.consumer_internet_status_on)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            WorkerField(state, onSave, onTurnOff)
        }
    }
}

@Composable
private fun WorkerField(state: InternetSyncUiState, onSave: (String) -> Boolean, onTurnOff: () -> Unit) {
    var address by remember(state.worker) { mutableStateOf(state.worker.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val save = { invalid = !onSave(address) }
    OutlinedTextField(
        value = address,
        onValueChange = {
            address = it
            invalid = false
        },
        label = { Text(stringResource(R.string.consumer_internet_worker_label)) },
        placeholder = { Text(stringResource(R.string.consumer_internet_worker_placeholder)) },
        supportingText = {
            Text(
                stringResource(
                    if (invalid) R.string.consumer_internet_worker_invalid else R.string.consumer_internet_worker_hint,
                ),
            )
        },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = save, enabled = address.isNotBlank() && address != state.worker) {
            Text(stringResource(R.string.consumer_internet_save))
        }
        if (state.worker != null) {
            OutlinedButton(onClick = onTurnOff) { Text(stringResource(R.string.consumer_internet_turn_off)) }
        }
    }
}

@Preview
@Composable
private fun InternetSyncScreenPreview() {
    MediaSyncTheme {
        InternetSyncScreen(
            state = InternetSyncUiState("https://mediasync.anna.workers.dev", reachable = true),
            onSave = { true },
            onTurnOff = {},
            onDone = {},
        )
    }
}
