/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.PendingApproval
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.engine.PairingLink
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface ConsumerPairingUiState {
    /** Not on Wi-Fi: there's no address to put in the code. */
    data object NoNetwork : ConsumerPairingUiState

    /** [approvalFrom] is the name of a phone waiting for the user's yes or no. */
    data class Showing(val link: String, val deviceName: String, val approvalFrom: String? = null) :
        ConsumerPairingUiState

    data class Paired(val producerName: String) : ConsumerPairingUiState
}

@HiltViewModel
class ConsumerPairingViewModel @Inject constructor(private val receiver: ConsumerReceiver) : ViewModel() {
    private val link = MutableStateFlow(open())

    // Starts from the real current values (never a placeholder that would flash "Connect to Wi-Fi"), and
    // updates eagerly: this view model lives exactly as long as its one screen.
    val uiState: StateFlow<ConsumerPairingUiState> = combine(
        link,
        receiver.pendingApproval,
        receiver.state,
        ::uiStateOf,
    ).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        uiStateOf(link.value, receiver.pendingApproval.value, receiver.state.value),
    )

    private fun uiStateOf(link: PairingLink?, pending: PendingApproval?, state: ReceiverState): ConsumerPairingUiState {
        val paired = state.lastPaired
        return when {
            paired != null -> ConsumerPairingUiState.Paired(paired.name)
            link == null -> ConsumerPairingUiState.NoNetwork
            else -> ConsumerPairingUiState.Showing(link.toString(), link.consumerName, pending?.producerName)
        }
    }

    fun accept() = receiver.pendingApproval.value?.accept()

    fun decline() = receiver.pendingApproval.value?.decline()

    fun retry() {
        link.value = open()
    }

    override fun onCleared() {
        receiver.closePairing()
        receiver.release(HOLDER)
    }

    private fun open(): PairingLink? {
        receiver.hold(HOLDER) // The producer connects here to pair.
        return receiver.openPairing()
    }

    private companion object {
        const val HOLDER = "pairing-screen"
    }
}

@Composable
fun ConsumerPairingRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConsumerPairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.pairing_share_chooser)
    ConsumerPairingScreen(
        state = state,
        onShareLink = { link ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            context.startActivity(Intent.createChooser(send, chooserTitle))
        },
        onAccept = viewModel::accept,
        onDecline = viewModel::decline,
        onRetry = viewModel::retry,
        onDone = onDone,
        modifier = modifier,
    )
}

/** The receiving phone shows a pairing code and confirms who scanned it (docs/PLAN.md §11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumerPairingScreen(
    state: ConsumerPairingUiState,
    onShareLink: (String) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { PairingTopBar(stringResource(R.string.pairing_consumer_title), onDone) },
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
                ConsumerPairingUiState.NoNetwork -> {
                    StatusCard(
                        title = stringResource(R.string.pairing_no_network_title),
                        body = stringResource(R.string.pairing_no_network_body),
                        icon = MediaSyncIcons.Info,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pairing_try_again))
                    }
                }

                is ConsumerPairingUiState.Showing -> ShowingCode(state, onShareLink, onAccept, onDecline)

                is ConsumerPairingUiState.Paired -> {
                    StatusCard(
                        title = stringResource(R.string.pairing_paired_title, state.producerName),
                        body = stringResource(R.string.pairing_paired_consumer_body),
                        icon = MediaSyncIcons.Receive,
                        tone = StatusTone.Positive,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pairing_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowingCode(
    state: ConsumerPairingUiState.Showing,
    onShareLink: (String) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Text(
        text = stringResource(R.string.pairing_consumer_instructions),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Card(shape = RoundedCornerShape(16.dp)) {
        QrCode(
            text = state.link,
            description = stringResource(R.string.pairing_qr_description),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        )
    }
    Text(text = state.deviceName, style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.pairing_consumer_validity),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = { onShareLink(state.link) }) { Text(stringResource(R.string.pairing_share_link)) }

    state.approvalFrom?.let { producer ->
        AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(stringResource(R.string.pairing_approve_title, producer)) },
            text = { Text(stringResource(R.string.pairing_approve_body)) },
            confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.pairing_approve_allow)) } },
            dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.pairing_approve_deny)) } },
        )
    }
}
