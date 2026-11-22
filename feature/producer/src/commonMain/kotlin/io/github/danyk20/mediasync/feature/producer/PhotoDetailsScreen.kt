/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

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
import androidx.compose.ui.semantics.Role
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
import io.github.danyk20.mediasync.core.designsystem.component.deviceText
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.feature.producer.generated.resources.Res
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_intro
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_intro_computer
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_intro_unpaired
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_intro_unpaired_computer
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_keep_all
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_keep_all_description
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_keep_all_description_computer
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_remove_all
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_remove_all_description
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_remove_all_description_computer
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_remove_all_note
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_remove_location
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_remove_location_description
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_scope
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_details_title
import io.github.danyk20.mediasync.feature.producer.generated.resources.producer_folders_done
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

data class PhotoDetailsUiState(
    val details: PhotoDetails = PhotoDetails.Default,
    /** `null` until a receiving phone is paired. */
    val consumerName: String? = null,
)

@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class PhotoDetailsViewModel(private val settings: SettingsRepository, peers: PeerStore) : ViewModel() {

    val uiState: StateFlow<PhotoDetailsUiState> =
        combine(settings.photoDetails, peers.observeConsumer()) { details, consumer ->
            PhotoDetailsUiState(details, consumer?.name)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PhotoDetailsUiState())

    fun choose(details: PhotoDetails) {
        viewModelScope.launch { settings.setPhotoDetails(details) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Composable
fun PhotoDetailsRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoDetailsViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PhotoDetailsScreen(state = state, onChoose = viewModel::choose, onDone = onDone, modifier = modifier)
}

/** *Photo details sent* (docs/PLAN.md §6.3): which details leave this phone with its photos and videos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailsScreen(
    state: PhotoDetailsUiState,
    onChoose: (PhotoDetails) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.producer_details_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(Res.string.producer_folders_done)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Explanation(
                state.consumerName?.let {
                    stringResource(
                        deviceText(Res.string.producer_details_intro, Res.string.producer_details_intro_computer),
                        it,
                    )
                } ?: stringResource(
                    deviceText(
                        Res.string.producer_details_intro_unpaired,
                        Res.string.producer_details_intro_unpaired_computer,
                    ),
                ),
            )
            Column(Modifier.selectableGroup()) {
                PhotoDetails.entries.forEach { details ->
                    Option(details, selected = state.details == details, onSelect = { onChoose(details) })
                }
            }
            if (state.details ==
                PhotoDetails.REMOVE_ALL
            ) {
                Explanation(stringResource(Res.string.producer_details_remove_all_note))
            }
            Explanation(stringResource(Res.string.producer_details_scope))
        }
    }
}

@Composable
private fun Option(details: PhotoDetails, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(details.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(details.description),
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

internal val PhotoDetails.title: StringResource
    get() = when (this) {
        PhotoDetails.KEEP_ALL -> Res.string.producer_details_keep_all
        PhotoDetails.REMOVE_LOCATION -> Res.string.producer_details_remove_location
        PhotoDetails.REMOVE_ALL -> Res.string.producer_details_remove_all
    }

private val PhotoDetails.description: StringResource
    @Composable get() = when (this) {
        PhotoDetails.KEEP_ALL -> deviceText(
            Res.string.producer_details_keep_all_description,
            Res.string.producer_details_keep_all_description_computer,
        )

        PhotoDetails.REMOVE_LOCATION -> Res.string.producer_details_remove_location_description

        PhotoDetails.REMOVE_ALL -> deviceText(
            Res.string.producer_details_remove_all_description,
            Res.string.producer_details_remove_all_description_computer,
        )
    }

@Preview
@Composable
private fun PhotoDetailsScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        PhotoDetailsScreen(
            state = PhotoDetailsUiState(PhotoDetails.REMOVE_ALL, "Pixel 2 XL"),
            onChoose = {},
            onDone = {},
        )
    }
}
