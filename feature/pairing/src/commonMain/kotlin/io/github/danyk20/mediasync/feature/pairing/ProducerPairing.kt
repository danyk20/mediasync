/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.feature.pairing.generated.resources.Res
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_camera_needed
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_confirm_body
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_confirm_cancel
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_confirm_pair
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_confirm_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_done
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_failed_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_not_a_code
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_paired_producer_body
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_paired_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_paste_link
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_problem_connection_lost
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_problem_declined
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_problem_expired
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_problem_incompatible
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_problem_no_answer
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_problem_unreachable
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_producer_instructions
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_producer_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_try_again
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_waiting_for_approval
import io.github.danyk20.mediasync.sync.android.PairingResult
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Why pairing didn't work, in terms of what the user can do about it. */
enum class PairingProblem { DECLINED, EXPIRED, NO_ANSWER, UNREACHABLE, CONNECTION_LOST, INCOMPATIBLE }

sealed interface ProducerPairingUiState {
    data class Scanning(val notAPairingCode: Boolean = false) : ProducerPairingUiState

    /**
     * A pairing link was opened (from a message or a website). Anyone could have sent it, so the user
     * confirms before this phone pairs with that receiver and starts sending it photos.
     */
    data class Confirm(val link: String, val consumerName: String) : ProducerPairingUiState

    data class Pairing(val consumerName: String) : ProducerPairingUiState

    data class Paired(val consumerName: String) : ProducerPairingUiState

    data class Failed(val problem: PairingProblem) : ProducerPairingUiState
}

/**
 * @param openedLink a `mediasync://pair` link opened from outside the app. It's applied once, when the
 *   screen opens, so rotating the phone mid-pairing doesn't start over.
 */
@AssistedInject
class ProducerPairingViewModel(private val sync: ProducerSync, @Assisted openedLink: String?) : ViewModel() {
    private val _uiState = MutableStateFlow(openedLink?.let(::confirmation) ?: ProducerPairingUiState.Scanning())
    val uiState: StateFlow<ProducerPairingUiState> = _uiState.asStateFlow()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(openedLink: String?): ProducerPairingViewModel
    }

    /**
     * Called for every code the camera reads (many per second, on a camera thread) and for pasted links.
     * Only the first valid code starts pairing. Scanning in person is the confirmation.
     */
    fun onCode(text: String) {
        val scanning = _uiState.value as? ProducerPairingUiState.Scanning ?: return
        val link = PairingLink.parse(text)
        if (link == null) {
            _uiState.compareAndSet(scanning, ProducerPairingUiState.Scanning(notAPairingCode = true))
            return
        }
        if (_uiState.compareAndSet(scanning, ProducerPairingUiState.Pairing(link.consumerName))) pair(link)
    }

    /** An opened link isn't scanned in person, so the user is asked before pairing. */
    private fun confirmation(text: String): ProducerPairingUiState {
        val link = PairingLink.parse(text) ?: return ProducerPairingUiState.Scanning(notAPairingCode = true)
        return ProducerPairingUiState.Confirm(text, link.consumerName)
    }

    /** The user confirmed pairing with the receiver from an opened link. */
    fun confirm() {
        val confirming = _uiState.value as? ProducerPairingUiState.Confirm ?: return
        val link = PairingLink.parse(confirming.link) ?: return
        if (_uiState.compareAndSet(confirming, ProducerPairingUiState.Pairing(link.consumerName))) pair(link)
    }

    private fun pair(link: PairingLink) {
        viewModelScope.launch {
            _uiState.value = when (val result = sync.pair(link)) {
                is PairingResult.Paired -> ProducerPairingUiState.Paired(result.consumerName)
                is PairingResult.Refused -> ProducerPairingUiState.Failed(result.decision.toProblem())
                is PairingResult.Failed -> ProducerPairingUiState.Failed(result.reason.toProblem())
            }
        }
    }

    fun retry() {
        _uiState.value = ProducerPairingUiState.Scanning()
    }

    private fun PairingDecision.toProblem() = when (this) {
        PairingDecision.INVALID_TOKEN -> PairingProblem.EXPIRED
        PairingDecision.TIMED_OUT -> PairingProblem.NO_ANSWER
        else -> PairingProblem.DECLINED
    }

    private fun SyncFailure.toProblem() = when (this) {
        SyncFailure.INCOMPATIBLE -> PairingProblem.INCOMPATIBLE
        SyncFailure.CONNECTION_LOST -> PairingProblem.CONNECTION_LOST
        else -> PairingProblem.UNREACHABLE
    }
}

/** @param openedLink a `mediasync://pair` link opened from outside the app, if that's how we got here. */
@Composable
fun ProducerPairingRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    openedLink: String? = null,
    viewModel: ProducerPairingViewModel =
        assistedMetroViewModel<ProducerPairingViewModel, ProducerPairingViewModel.Factory> {
            create(openedLink)
        },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanner = rememberQrScanner(active = state is ProducerPairingUiState.Scanning, onCode = viewModel::onCode)
    val readClipboard = rememberClipboardReader()
    val scope = rememberCoroutineScope()

    ProducerPairingScreen(
        state = state,
        camera = scanner,
        onPasteLink = { scope.launch { viewModel.onCode(readClipboard()) } },
        onConfirm = viewModel::confirm,
        onRetry = viewModel::retry,
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * The sending phone scans the receiving phone's code.
 *
 * @param camera the live scanner, or `null` without camera permission (pasting the link still works).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProducerPairingScreen(
    state: ProducerPairingUiState,
    camera: (@Composable () -> Unit)?,
    onPasteLink: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { PairingTopBar(stringResource(Res.string.pairing_producer_title), onDone) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is ProducerPairingUiState.Scanning -> Scanning(state, camera, onPasteLink)

                is ProducerPairingUiState.Confirm -> Confirm(state.consumerName, onConfirm, onCancel = onDone)

                is ProducerPairingUiState.Pairing -> {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(Res.string.pairing_waiting_for_approval, state.consumerName),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }

                is ProducerPairingUiState.Paired -> {
                    StatusCard(
                        title = stringResource(Res.string.pairing_paired_title, state.consumerName),
                        body = stringResource(Res.string.pairing_paired_producer_body),
                        icon = MediaSyncIcons.Send,
                        tone = StatusTone.Positive,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pairing_done))
                    }
                }

                is ProducerPairingUiState.Failed -> {
                    StatusCard(
                        title = stringResource(Res.string.pairing_failed_title),
                        body = problemMessage(state.problem),
                        icon = MediaSyncIcons.Info,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pairing_try_again))
                    }
                }
            }
        }
    }
}

/** A link opened from outside the app pairs only after the user confirms it's their own receiving phone. */
@Composable
private fun Confirm(consumerName: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    StatusCard(
        title = stringResource(Res.string.pairing_confirm_title, consumerName),
        body = stringResource(Res.string.pairing_confirm_body, consumerName),
        icon = MediaSyncIcons.Info,
    )
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.pairing_confirm_pair))
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.pairing_confirm_cancel))
    }
}

@Composable
private fun Scanning(
    state: ProducerPairingUiState.Scanning,
    camera: (@Composable () -> Unit)?,
    onPasteLink: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.pairing_producer_instructions),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (camera != null) {
                camera()
            } else {
                Text(
                    text = stringResource(Res.string.pairing_camera_needed),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
    if (state.notAPairingCode) {
        Text(
            text = stringResource(Res.string.pairing_not_a_code),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    OutlinedButton(onClick = onPasteLink) { Text(stringResource(Res.string.pairing_paste_link)) }
}

@Composable
private fun problemMessage(problem: PairingProblem): String = stringResource(
    when (problem) {
        PairingProblem.DECLINED -> Res.string.pairing_problem_declined
        PairingProblem.EXPIRED -> Res.string.pairing_problem_expired
        PairingProblem.NO_ANSWER -> Res.string.pairing_problem_no_answer
        PairingProblem.UNREACHABLE -> Res.string.pairing_problem_unreachable
        PairingProblem.CONNECTION_LOST -> Res.string.pairing_problem_connection_lost
        PairingProblem.INCOMPATIBLE -> Res.string.pairing_problem_incompatible
    },
)
