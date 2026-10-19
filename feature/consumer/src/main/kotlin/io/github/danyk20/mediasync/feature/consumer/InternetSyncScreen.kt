/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.component.formatBytes
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.android.SetupFailure
import io.github.danyk20.mediasync.sync.android.SetupResult
import io.github.danyk20.mediasync.sync.android.SetupStep
import io.github.danyk20.mediasync.sync.android.WorkerSetup
import io.github.danyk20.mediasync.transport.rendezvous.CloudflareOAuth
import java.net.URI
import java.net.URISyntaxException
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the internet sync screen shows. */
data class InternetSyncUiState(
    /** The receiving phone's rendezvous Worker; `null` while internet sync is off. */
    val worker: String? = null,
    /** Paired phones can reach this phone through the Worker now. */
    val reachable: Boolean = false,
    /** This month's relay usage (docs/PLAN.md §8.5); `null` until known. */
    val relay: RelayUsage? = null,
    /** Setting it up with the user's Cloudflare account. */
    val setup: CloudflareSetup = CloudflareSetup.Unavailable,
    /** This build can sign in with Cloudflare for it, instead of taking a token. */
    val signIn: Boolean = false,
)

/**
 * Internet sync (docs/PLAN.md §8.4), as the receiving phone sets it up: the rendezvous Worker its sending phones
 * find it through when they aren't on its Wi-Fi. Each household runs its own, in its own Cloudflare account.
 */
@HiltViewModel
class InternetSyncViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val receiver: ConsumerReceiver,
    private val workerSetup: WorkerSetup,
    private val cloudflare: CloudflareSignIn,
    /** Keeps a sign-in under way while the browser is open, even if Android ends the app meanwhile. */
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val relay = MutableStateFlow<RelayUsage?>(null)
    private val setup = MutableStateFlow(
        when {
            !workerSetup.available() -> CloudflareSetup.Unavailable
            pending() != null -> CloudflareSetup.SigningIn
            else -> CloudflareSetup.Ready
        },
    )
    private val canSignIn = workerSetup.available() && cloudflare.available

    /** `null` while loading. */
    val uiState: StateFlow<InternetSyncUiState?> =
        combine(settings.rendezvousUrl, receiver.state, relay, setup) { worker, state, relay, setup ->
            val on = worker != null
            InternetSyncUiState(worker, on && state.overInternet, relay?.takeIf { on }, setup, canSignIn)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // The meter moves while relayed transfers run: read it again every minute, and for a new Worker at once.
        viewModelScope.launch {
            settings.rendezvousUrl.collectLatest { worker ->
                relay.value = null
                while (worker != null) {
                    relay.value = withContext(Dispatchers.IO) { receiver.relayUsage() }
                    delay(RELAY_REFRESH_MILLIS)
                }
            }
        }
    }

    /** Saves the Worker at [address]; `false` (and nothing saved) if it isn't an `https` address. */
    fun save(address: String): Boolean {
        val worker = workerAddress(address) ?: return false
        update(worker)
        return true
    }

    fun turnOff() = update(null)

    /** Deploys the Worker into the user's Cloudflare account with [token], and turns internet sync on with it. */
    fun setUp(token: String) {
        if (busy()) return
        deploy(token)
    }

    /**
     * Starts signing in with Cloudflare: the address of its sign-in page, for the browser to open; `null` if this
     * build can't, or something is under way. The answer comes in [returned].
     */
    fun signIn(): String? {
        if (!canSignIn || busy()) return null
        val pending = cloudflare.begin() ?: return null
        cloudflare.take() // An earlier answer isn't this sign-in's.
        saved[VERIFIER] = pending.verifier
        saved[STATE] = pending.state
        setup.value = CloudflareSetup.SigningIn
        return cloudflare.authorizeUrl(pending)
    }

    /**
     * Back on the screen: with a sign-in under way, the browser's answer is turned into a token to deploy with. Back
     * without one, the sign-in was cancelled.
     */
    fun returned() {
        val redirect = cloudflare.take()
        val pending = pending() ?: return
        saved.remove<String>(VERIFIER)
        saved.remove<String>(STATE)
        if (redirect == null) {
            setup.value = CloudflareSetup.Finished(SetupResult.Failed(SetupFailure.SIGN_IN_CANCELLED))
            return
        }
        setup.value = CloudflareSetup.SigningIn
        viewModelScope.launch {
            when (val result = cloudflare.token(redirect, pending)) {
                is SignInResult.Token -> deploy(result.token, signedIn = true)

                is SignInResult.Failed ->
                    setup.value = CloudflareSetup.Finished(SetupResult.Failed(result.reason, result.detail))
            }
        }
    }

    private fun deploy(token: String, signedIn: Boolean = false) {
        setup.value = CloudflareSetup.Running(SetupStep.ACCOUNT)
        viewModelScope.launch {
            val result = workerSetup.deploy(token) { step -> setup.value = CloudflareSetup.Running(step) }
            setup.value = CloudflareSetup.Finished(result, signedIn)
        }
    }

    private fun busy() = when (setup.value) {
        CloudflareSetup.Unavailable, CloudflareSetup.SigningIn, is CloudflareSetup.Running -> true
        CloudflareSetup.Ready, is CloudflareSetup.Finished -> false
    }

    private fun pending(): CloudflareOAuth.Pending? {
        val verifier = saved.get<String>(VERIFIER) ?: return null
        val state = saved.get<String>(STATE) ?: return null
        return CloudflareOAuth.Pending(verifier, state)
    }

    /** The outcome was read: back to the token steps. */
    fun dismissSetup() {
        if (setup.value is CloudflareSetup.Finished) setup.value = CloudflareSetup.Ready
    }

    private fun update(worker: String?) {
        viewModelScope.launch {
            settings.setRendezvousUrl(worker)
            receiver.refreshInternet()
        }
    }

    private companion object {
        const val RELAY_REFRESH_MILLIS = 60_000L
        const val VERIFIER = "cloudflare_sign_in_verifier"
        const val STATE = "cloudflare_sign_in_state"
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
    val links = LocalUriHandler.current
    val context = LocalContext.current
    // Back from the browser, or from anywhere else: a sign-in under way gets its answer.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.returned() }
    InternetSyncScreen(
        state = state,
        actions = InternetSyncActions(
            save = viewModel::save,
            turnOff = viewModel::turnOff,
            setUp = viewModel::setUp,
            signIn = {
                viewModel.signIn()?.let { url ->
                    try {
                        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
                    } catch (_: ActivityNotFoundException) {
                        viewModel.returned() // No browser: nothing to sign in with.
                    }
                }
            },
            openTokenPage = { links.openUri(WorkerSetup.TOKEN_URL) },
            dismissSetup = viewModel::dismissSetup,
        ),
        onDone = onDone,
        modifier = modifier,
    )
}

/** The receiving phone's internet sync setting. [state] `null`: still loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternetSyncScreen(
    state: InternetSyncUiState?,
    actions: InternetSyncActions,
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
            Setup(state, actions)
            state.relay?.let { RelayMeter(it) }
        }
    }
}

/**
 * Without a Worker: set one up with the user's Cloudflare account, or enter the address of one they deployed
 * themselves. With one: its address, to change or turn off, and how setting it up went.
 */
@Composable
private fun Setup(state: InternetSyncUiState, actions: InternetSyncActions) {
    var ownWorker by rememberSaveable { mutableStateOf(false) }
    val setup = state.setup
    val deploying = setup != CloudflareSetup.Unavailable
    when {
        state.worker != null -> {
            WorkerField(state, actions)
            if (setup is CloudflareSetup.Finished) CloudflareSetupCard(setup, state.signIn, actions)
        }

        !deploying || ownWorker -> WorkerField(state, actions)

        else -> {
            CloudflareSetupCard(setup, state.signIn, actions)
            if (setup !is CloudflareSetup.Running && setup != CloudflareSetup.SigningIn) {
                TextButton(onClick = { ownWorker = true }) {
                    Text(stringResource(R.string.consumer_internet_own_worker))
                }
            }
        }
    }
}

@Composable
private fun WorkerField(state: InternetSyncUiState, actions: InternetSyncActions) {
    var address by remember(state.worker) { mutableStateOf(state.worker.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val save = { invalid = !actions.save(address) }
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
            OutlinedButton(onClick = actions.turnOff) { Text(stringResource(R.string.consumer_internet_turn_off)) }
        }
    }
}

/** The relay allowance this month (docs/PLAN.md §8.5). */
@Composable
private fun RelayMeter(relay: RelayUsage) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.consumer_relay_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.consumer_relay_used, gigabytes(relay.usedBytes), gigabytes(relay.freeBytes)),
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { (relay.usedBytes.toFloat() / relay.freeBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (relay.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            val colors = MaterialTheme.colorScheme
            val tone = if (relay.available) colors.onSurfaceVariant else colors.error
            val resets = relay.resetsAt?.let {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it.atZone(ZoneId.systemDefault()))
            }
            Text(
                text = when {
                    !relay.available && resets != null -> stringResource(R.string.consumer_relay_used_up, resets)
                    resets != null -> stringResource(R.string.consumer_relay_limit, gigabytes(relay.limitBytes), resets)
                    else -> stringResource(R.string.consumer_relay_limit_no_date, gigabytes(relay.limitBytes))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tone,
            )
            Text(
                stringResource(R.string.consumer_relay_free_paths),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Relay amounts as Cloudflare states its allowance: "312 GB", "1,000 GB"; below a gigabyte, as file sizes. */
internal fun gigabytes(bytes: Long): String = if (bytes < BYTES_PER_GB) {
    formatBytes(bytes)
} else {
    "${NumberFormat.getIntegerInstance().format(bytes / BYTES_PER_GB)} GB"
}

private const val BYTES_PER_GB = 1_000_000_000L

@Preview
@Composable
private fun InternetSyncScreenPreview() {
    MediaSyncTheme {
        InternetSyncScreen(
            state = InternetSyncUiState("https://mediasync.anna.workers.dev", reachable = true),
            actions = InternetSyncActions(),
            onDone = {},
        )
    }
}
