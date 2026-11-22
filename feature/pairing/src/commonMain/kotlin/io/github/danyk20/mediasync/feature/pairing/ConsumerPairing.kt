/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.danyk20.mediasync.core.designsystem.component.LocalIsComputer
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.component.deviceText
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.feature.pairing.generated.resources.Res
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_approve_allow
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_approve_body
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_approve_body_computer
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_approve_deny
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_approve_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_consumer_instructions
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_consumer_instructions_computer
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_consumer_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_consumer_title_computer
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_consumer_validity
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_consumer_validity_computer
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_copy_link
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_done
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_link_copied
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_no_network_body
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_no_network_body_computer
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_no_network_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_paired_consumer_body
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_paired_title
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_qr_description
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_share_link
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_try_again
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.PendingApproval
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.engine.PairingLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource

sealed interface ConsumerPairingUiState {
    /** Not on Wi-Fi: there's no address to put in the code. */
    data object NoNetwork : ConsumerPairingUiState

    /** [approvalFrom] is the name of a phone waiting for the user's yes or no. */
    data class Showing(val link: String, val deviceName: String, val approvalFrom: String? = null) :
        ConsumerPairingUiState

    data class Paired(val producerName: String) : ConsumerPairingUiState
}

@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class ConsumerPairingViewModel(private val receiver: ConsumerReceiver) : ViewModel() {
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
    viewModel: ConsumerPairingViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shareLink = rememberLinkSharer()
    ConsumerPairingScreen(
        state = state,
        onShareLink = shareLink,
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
        topBar = {
            PairingTopBar(
                stringResource(
                    deviceText(Res.string.pairing_consumer_title, Res.string.pairing_consumer_title_computer),
                ),
                onDone,
            )
        },
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
                        title = stringResource(Res.string.pairing_no_network_title),
                        body = stringResource(
                            deviceText(Res.string.pairing_no_network_body, Res.string.pairing_no_network_body_computer),
                        ),
                        icon = MediaSyncIcons.Info,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pairing_try_again))
                    }
                }

                is ConsumerPairingUiState.Showing -> ShowingCode(state, onShareLink, onAccept, onDecline)

                is ConsumerPairingUiState.Paired -> {
                    StatusCard(
                        title = stringResource(Res.string.pairing_paired_title, state.producerName),
                        body = stringResource(Res.string.pairing_paired_consumer_body),
                        icon = MediaSyncIcons.Receive,
                        tone = StatusTone.Positive,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pairing_done))
                    }
                }
            }
        }
    }
}

/** How long "Link copied" stays before the button says "Copy link" again. */
private const val LINK_COPIED_MILLIS = 3_000L

/** On a computer the button copies the link and says so; on a phone it shares the link. */
@Composable
private fun ShareLinkButton(link: String, onShareLink: (String) -> Unit) {
    val computer = LocalIsComputer.current
    // A counter, not a flag: a second click restarts the three seconds.
    var copies by remember(link) { mutableIntStateOf(0) }
    val copied = copies > 0
    LaunchedEffect(copies) {
        if (copies > 0) {
            delay(LINK_COPIED_MILLIS)
            copies = 0
        }
    }
    OutlinedButton(
        onClick = {
            onShareLink(link)
            copies++
        },
    ) {
        Text(
            stringResource(
                when {
                    !computer -> Res.string.pairing_share_link
                    copied -> Res.string.pairing_link_copied
                    else -> Res.string.pairing_copy_link
                },
            ),
        )
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
        text = stringResource(
            deviceText(Res.string.pairing_consumer_instructions, Res.string.pairing_consumer_instructions_computer),
        ),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Card(shape = RoundedCornerShape(16.dp)) {
        QrCode(
            text = state.link,
            description = stringResource(Res.string.pairing_qr_description),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        )
    }
    Text(text = state.deviceName, style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(
            deviceText(Res.string.pairing_consumer_validity, Res.string.pairing_consumer_validity_computer),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    ShareLinkButton(state.link, onShareLink)

    state.approvalFrom?.let { producer ->
        AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(stringResource(Res.string.pairing_approve_title, producer)) },
            text = {
                Text(
                    stringResource(
                        deviceText(Res.string.pairing_approve_body, Res.string.pairing_approve_body_computer),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onAccept) { Text(stringResource(Res.string.pairing_approve_allow)) }
            },
            dismissButton = {
                TextButton(onClick = onDecline) { Text(stringResource(Res.string.pairing_approve_deny)) }
            },
        )
    }
}
