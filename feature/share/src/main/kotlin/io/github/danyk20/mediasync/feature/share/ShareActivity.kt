/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.share

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the share dialog shows. */
sealed interface ShareState {
    /** Copying files that aren't in the gallery: [done] of [total]. */
    data class Preparing(val done: Int, val total: Int) : ShareState

    /** Queued and a sync started, to [consumer]. */
    data class Sending(val queued: Queued, val consumer: String?) : ShareState
}

@HiltViewModel
class ShareViewModel @Inject constructor(private val queue: ShareQueue, private val peers: PeerStore) : ViewModel() {
    private val _state = MutableStateFlow<ShareState>(ShareState.Preparing(0, 0))
    val state: StateFlow<ShareState> = _state.asStateFlow()
    private var started = false

    /** Queues [uris] once, however often the screen is created. */
    fun share(uris: List<Uri>) {
        if (started) return
        started = true
        _state.value = ShareState.Preparing(0, uris.size)
        viewModelScope.launch(Dispatchers.IO) {
            val queued = queue.queue(uris) { done -> _state.value = ShareState.Preparing(done, uris.size) }
            val consumer = peers.observeAll().first().firstOrNull { it.role == DeviceRole.CONSUMER }?.name
            _state.value = ShareState.Sending(queued, consumer)
        }
    }
}

/**
 * MediaSync in the share sheet of a gallery app (docs/PLAN.md, M6): the photos and videos shared go to the receiving
 * phone now. Only on a paired sending phone; [ShareTarget] switches it on and off.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.share(intent.sharedUris())
        setContent {
            MediaSyncTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ShareDialog(state, onDone = ::finish)
            }
        }
    }
}

@Composable
fun ShareDialog(state: ShareState, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (state is ShareState.Sending) onDone() },
        confirmButton = {
            if (state is ShareState.Sending) TextButton(onClick = onDone) { Text(stringResource(R.string.share_ok)) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is ShareState.Preparing -> {
                        Text(stringResource(R.string.share_preparing, state.done, state.total))
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is ShareState.Sending -> Sending(state)
                }
            }
        },
    )
}

@Composable
private fun Sending(state: ShareState.Sending) {
    val queued = state.queued
    if (queued.items == 0) {
        Text(stringResource(R.string.share_nothing))
    } else {
        val consumer = state.consumer ?: stringResource(R.string.share_receiving_phone)
        Text(
            pluralStringResource(R.plurals.share_sending, queued.items, queued.items, consumer),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.share_unreachable_note), style = MaterialTheme.typography.bodyMedium)
    }
    if (queued.unreadable > 0) {
        Text(
            pluralStringResource(R.plurals.share_unreadable, queued.unreadable, queued.unreadable),
            color = MaterialTheme.colorScheme.error,
        )
    }
}
