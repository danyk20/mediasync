/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.designsystem.component.formatBytes
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.MediaFolder
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.core.model.SendRange
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder on the list, and whether it's sent. */
data class FolderChoice(val folder: MediaFolder, val included: Boolean)

sealed interface SourceFoldersUiState {
    data object Loading : SourceFoldersUiState

    data class Ready(
        val selection: SourceSelection,
        val folders: List<FolderChoice>,
        /** The days whose photos and videos are sent. */
        val range: SendRange = SendRange.Everything,
    ) : SourceFoldersUiState
}

/** Which folders this phone sends (docs/PLAN.md §2.2), and from which days. Every change is saved at once. */
@HiltViewModel
class SourceFoldersViewModel @Inject constructor(
    private val library: MediaLibrary,
    private val settings: SettingsRepository,
) : ViewModel() {

    @OptIn(FlowPreview::class)
    private val folders = flow {
        emit(library.folders())
        library.changes().debounce(LIBRARY_SETTLE_MILLIS).collect { emit(library.folders()) }
    }

    val uiState: StateFlow<SourceFoldersUiState> = combine(
        folders,
        settings.sourceSelection,
        settings.sendRange,
    ) { folders, selection, range ->
        SourceFoldersUiState.Ready(selection, folders.map { FolderChoice(it, selection.includes(it.path)) }, range)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SourceFoldersUiState.Loading)

    fun choose(preset: SourcePreset) {
        viewModelScope.launch { settings.setSourceSelection(SourceSelection(preset)) }
    }

    /** Sends only what was taken within [range]; [SendRange.Everything] sends everything again. */
    fun choose(range: SendRange) {
        viewModelScope.launch { settings.setSendRange(range) }
    }

    /** Turns one folder on or off. From a preset, this keeps the folders it covers now and drops the preset. */
    fun toggle(path: String) {
        val state = uiState.value as? SourceFoldersUiState.Ready ?: return
        val chosen = if (state.selection.preset == null) {
            state.selection.folders
        } else {
            state.folders.filter { it.included }.map { it.folder.path }.toSet()
        }
        val next = if (path in chosen) chosen - path else chosen + path
        viewModelScope.launch { settings.setSourceSelection(SourceSelection.of(next)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Library changes come in bursts (a photo is inserted, written, then indexed); wait for the last one. */
internal const val LIBRARY_SETTLE_MILLIS = 500L

@Composable
fun SourceFoldersRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceFoldersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SourceFoldersScreen(
        state = state,
        onChoosePreset = viewModel::choose,
        onToggle = viewModel::toggle,
        onDone = onDone,
        modifier = modifier,
        onChooseRange = viewModel::choose,
    )
}

/** The list of this phone's photo and video folders, to choose which ones are sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFoldersScreen(
    state: SourceFoldersUiState,
    onChoosePreset: (SourcePreset) -> Unit,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseRange: (SendRange) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.producer_folders_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.producer_folders_done)) } },
            )
        },
    ) { padding ->
        when (state) {
            SourceFoldersUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }

            is SourceFoldersUiState.Ready -> LazyColumn(contentPadding = padding) {
                item { Presets(state.selection, onChoosePreset) }
                item { RangeChoice(state.range, onChooseRange) }
                if (state.folders.isEmpty()) {
                    item { Explanation(stringResource(R.string.producer_folders_empty)) }
                }
                items(state.folders, key = { it.folder.path }) { choice -> FolderRow(choice, onToggle) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(selection: SourceSelection, onChoosePreset: (SourcePreset) -> Unit) {
    Explanation(stringResource(R.string.producer_folders_intro))
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourcePreset.entries.forEach { preset ->
            FilterChip(
                selected = selection.preset == preset,
                onClick = { onChoosePreset(preset) },
                label = { Text(presetName(preset)) },
            )
        }
    }
    Explanation(
        stringResource(
            if (selection.preset ==
                null
            ) {
                R.string.producer_folders_hint_chosen
            } else {
                R.string.producer_folders_hint_preset
            },
        ),
    )
}

/** Which days this phone sends from: any, or from a first day, until a last day, or both, both days included. */
@Composable
private fun RangeChoice(range: SendRange, onChooseRange: (SendRange) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<RangeEnd?>(null) }
    Text(
        text = stringResource(R.string.producer_range_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .semantics { heading() },
    )
    RangeEndRow(
        label = stringResource(R.string.producer_range_from),
        open = stringResource(R.string.producer_range_no_start),
        day = range.from,
        onOpen = { onChooseRange(range.copy(from = null)) },
        onPick = { picking = RangeEnd.FROM },
    )
    RangeEndRow(
        label = stringResource(R.string.producer_range_until),
        open = stringResource(R.string.producer_range_no_end),
        day = range.until,
        onOpen = { onChooseRange(range.copy(until = null)) },
        onPick = { picking = RangeEnd.UNTIL },
    )
    Explanation(rangeNote(range))
    if (!range.isEverything) Explanation(stringResource(R.string.producer_range_backup_note))
    picking?.let { end ->
        DayDialog(
            end = end,
            range = range,
            onPick = { day ->
                picking = null
                onChooseRange(if (end == RangeEnd.FROM) range.copy(from = day) else range.copy(until = day))
            },
            onDismiss = { picking = null },
        )
    }
}

private enum class RangeEnd { FROM, UNTIL }

/** One end of the range: open, or a chosen day; tapping the day chip (again) picks one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeEndRow(label: String, open: String, day: LocalDate?, onOpen: () -> Unit, onPick: () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(RANGE_LABEL_WIDTH))
        FilterChip(selected = day == null, onClick = onOpen, label = { Text(open) })
        FilterChip(
            selected = day != null,
            onClick = onPick,
            label = { Text(day?.format(DAY_SHOWN) ?: stringResource(R.string.producer_range_choose_day)) },
        )
    }
}

/** What's sent, in words that say whether the chosen days themselves are: they always are. */
@Composable
private fun rangeNote(range: SendRange): String {
    val from = range.from?.format(DAY_SHOWN)
    val until = range.until?.format(DAY_SHOWN)
    return when {
        from != null && until != null -> stringResource(R.string.producer_range_note_between, from, until)
        from != null -> stringResource(R.string.producer_range_note_from, from)
        until != null -> stringResource(R.string.producer_range_note_until, until)
        else -> stringResource(R.string.producer_range_note_everything)
    }
}

/**
 * Picks the first or last day sent: today at first. The first day can't come after the last one, or after today;
 * the last day can't come before the first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDialog(end: RangeEnd, range: SendRange, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val earliest = if (end == RangeEnd.UNTIL) range.from else null
    val latest = if (end == RangeEnd.FROM) listOfNotNull(today, range.until).min() else null
    val shown = (if (end == RangeEnd.FROM) range.from else range.until) ?: latest?.takeIf { it < today } ?: today
    val state = rememberDatePickerState(
        initialSelectedDateMillis = shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = utcTimeMillis.toUtcDay()
                return (earliest == null || day >= earliest) && (latest == null || day <= latest)
            }

            override fun isSelectableYear(year: Int) =
                (earliest == null || year >= earliest.year) && (latest == null || year <= latest.year)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(it.toUtcDay()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    stringResource(
                        if (end ==
                            RangeEnd.FROM
                        ) {
                            R.string.producer_range_pick_from
                        } else {
                            R.string.producer_range_pick_until
                        },
                    ),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

/** The date picker works in UTC days. */
private fun Long.toUtcDay(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** A day as the user's language writes it, e.g. "Jan 1, 2025". */
internal val DAY_SHOWN: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private val RANGE_LABEL_WIDTH = 56.dp

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
    )
}

@Composable
private fun FolderRow(choice: FolderChoice, onToggle: (String) -> Unit) {
    val folder = choice.folder
    ListItem(
        headlineContent = { Text(folder.name) },
        supportingContent = {
            val items = NumberFormat.getIntegerInstance().format(folder.itemCount)
            Text(
                text = stringResource(
                    R.string.producer_folders_details,
                    pluralStringResource(R.plurals.producer_folders_items, folder.itemCount, items),
                    formatBytes(folder.byteCount),
                    folder.path.trimEnd('/'),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { FolderThumbnail(folder.coverUri) },
        // The whole row toggles; the checkbox only shows the state.
        trailingContent = { Checkbox(checked = choice.included, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = choice.included,
            role = Role.Checkbox,
            onValueChange = { onToggle(folder.path) },
        ),
    )
}

/** The folder's newest photo or video, or a folder icon while it loads or if it can't be read. */
@Composable
private fun FolderThumbnail(coverUri: String?) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { THUMBNAIL_SIZE.roundToPx() }
    val thumbnail by produceState<ImageBitmap?>(null, coverUri) {
        value = coverUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(uri.toUri(), Size(sizePx, sizePx), null).asImageBitmap()
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumbnail
        if (image == null) {
            Icon(
                painter = painterResource(MediaSyncIcons.Folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop)
        }
    }
}

private val THUMBNAIL_SIZE = 56.dp

@Composable
internal fun presetName(preset: SourcePreset): String = stringResource(
    when (preset) {
        SourcePreset.CAMERA -> R.string.producer_folders_preset_camera
        SourcePreset.CAMERA_AND_SCREENSHOTS -> R.string.producer_folders_preset_camera_screenshots
        SourcePreset.EVERYTHING -> R.string.producer_folders_preset_everything
    },
)

@Preview
@Composable
private fun SourceFoldersScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        SourceFoldersScreen(
            state = SourceFoldersUiState.Ready(
                SourceSelection.Default,
                listOf(
                    FolderChoice(MediaFolder("DCIM/Camera/", 2_814, 9_400_000_000, null), included = true),
                    FolderChoice(MediaFolder("Pictures/Screenshots/", 412, 310_000_000, null), included = false),
                ),
            ),
            onChoosePreset = {},
            onToggle = {},
            onDone = {},
        )
    }
}
