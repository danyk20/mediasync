/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SendingOrder
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SendingOrderViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {

    val uiState: StateFlow<SendingOrder> = settings.sendingOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SendingOrder.Default)

    fun choose(order: SendingOrder) {
        viewModelScope.launch { settings.setSendingOrder(order) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Composable
fun SendingOrderRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SendingOrderViewModel = hiltViewModel(),
) {
    val order by viewModel.uiState.collectAsStateWithLifecycle()
    SendingOrderScreen(order = order, onChoose = viewModel::choose, onDone = onDone, modifier = modifier)
}

/** *Sending order*: what this phone sends first when a lot is waiting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendingOrderScreen(
    order: SendingOrder,
    onChoose: (SendingOrder) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.producer_order_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.producer_folders_done)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Explanation(stringResource(R.string.producer_order_intro))
            Column(Modifier.selectableGroup()) {
                SendingOrder.entries.forEach { option ->
                    Option(option, selected = order == option, onSelect = { onChoose(option) })
                }
            }
            Explanation(stringResource(R.string.producer_order_note))
        }
    }
}

@Composable
private fun Option(order: SendingOrder, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(order.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(order.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@get:StringRes
internal val SendingOrder.title: Int
    get() = when (this) {
        SendingOrder.AUTOMATIC -> R.string.producer_order_automatic
        SendingOrder.NEWEST_FIRST -> R.string.producer_order_newest_first
        SendingOrder.OLDEST_FIRST -> R.string.producer_order_oldest_first
        SendingOrder.PHOTOS_FIRST -> R.string.producer_order_photos_first
        SendingOrder.VIDEOS_FIRST -> R.string.producer_order_videos_first
    }

@get:StringRes
private val SendingOrder.description: Int
    get() = when (this) {
        SendingOrder.AUTOMATIC -> R.string.producer_order_automatic_description
        SendingOrder.NEWEST_FIRST -> R.string.producer_order_newest_first_description
        SendingOrder.OLDEST_FIRST -> R.string.producer_order_oldest_first_description
        SendingOrder.PHOTOS_FIRST -> R.string.producer_order_photos_first_description
        SendingOrder.VIDEOS_FIRST -> R.string.producer_order_videos_first_description
    }

@Preview
@Composable
private fun SendingOrderScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        SendingOrderScreen(order = SendingOrder.AUTOMATIC, onChoose = {}, onDone = {})
    }
}
