/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.text.format.DateFormat
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.sync.android.LeftOutItems
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.engine.SourceItem
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo or video waiting to sync, as the list shows it. */
data class WaitingMedia(
    val key: String,
    /** To show and open it: without asking for the original, which only sending needs. */
    val uri: String,
    val video: Boolean,
    /** When it was taken, or else when its file last changed. */
    val takenAtMillis: Long,
)

sealed interface WaitingUiState {
    data object Loading : WaitingUiState

    /** Both lists newest first; [selected]: the keys of those chosen, on either list. */
    data class Ready(
        val toSync: List<WaitingMedia>,
        val leftOut: List<WaitingMedia>,
        val selected: Set<String> = emptySet(),
    ) : WaitingUiState
}

/** Which list is shown: what goes at the next sync, or what the user left out. */
enum class WaitingList { TO_SYNC, LEFT_OUT }

/** What the user just did, to say so and offer to undo it: [count] photos and videos left out, or brought back. */
data class WaitingChange(val leftOut: Boolean, val count: Int)

/**
 * The photos and videos the next sync sends, and those the user chose not to sync ([LeftOutItems]). The user chooses
 * some and leaves them out, or brings them back. Each change is saved at once, and can be undone.
 */
@HiltViewModel
class WaitingViewModel @Inject constructor(
    private val sync: ProducerSync,
    private val leftOut: LeftOutItems,
    library: MediaLibrary,
) : ViewModel() {

    private val items = MutableStateFlow<List<SourceItem>?>(null)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val done = Channel<WaitingChange>(Channel.BUFFERED)
    private var undoing: (suspend () -> Unit)? = null

    /** Each change as it's saved. */
    val changes: Flow<WaitingChange> = done.receiveAsFlow()

    val uiState: StateFlow<WaitingUiState> = combine(items, leftOut.fingerprints, selected) { items, left, selected ->
        if (items == null) return@combine WaitingUiState.Loading
        val out = leftOut.of(items, left).toSet()
        val (leftOutItems, toSync) = items.partition { it in out }
        WaitingUiState.Ready(toSync.shown(), leftOutItems.shown(), selected)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WaitingUiState.Loading)

    init {
        // Listed again when photos are added or deleted, and when a sync ends: what it sent no longer waits.
        @OptIn(FlowPreview::class)
        val reasons = merge(
            flowOf(Unit),
            library.changes().debounce(LIBRARY_SETTLE_MILLIS),
            sync.state.map { it is ProducerState.Idle }.distinctUntilChanged().drop(1).filter { it }.map { },
        )
        viewModelScope.launch { reasons.collectLatest { items.value = sync.waitingItems() } }
    }

    fun toggle(key: String) = selected.update { if (key in it) it - key else it + key }

    /** Chooses all of [keys], or none of them. */
    fun select(keys: Collection<String>, chosen: Boolean) =
        selected.update { if (chosen) it + keys else it - keys.toSet() }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /** What's chosen, all on [list] (the screen chooses on one list at a time), moves to the other one. */
    fun moveSelected(list: WaitingList) {
        val keys = selected.value
        val chosen = items.value.orEmpty().filter { it.key in keys }
        clearSelection()
        if (chosen.isEmpty()) return
        val leavingOut = list == WaitingList.TO_SYNC
        undoing = if (leavingOut) suspend { leftOut.bringBack(chosen) } else suspend { leftOut.leaveOut(chosen) }
        viewModelScope.launch {
            if (leavingOut) leftOut.leaveOut(chosen) else leftOut.bringBack(chosen)
            done.send(WaitingChange(leavingOut, chosen.size))
        }
    }

    /** Takes back the last change. */
    fun undo() {
        val undo = undoing ?: return
        undoing = null
        viewModelScope.launch { undo() }
    }

    private fun List<SourceItem>.shown(): List<WaitingMedia> = map { item ->
        val meta = item.descriptor.meta
        WaitingMedia(
            key = item.key,
            uri = item.key.toUri().buildUpon().clearQuery().build().toString(),
            video = item.descriptor.mimeType.startsWith("video/"),
            takenAtMillis = meta.capturedAtMillis ?: meta.modifiedAtMillis ?: 0,
        )
    }.sortedByDescending { it.takenAtMillis }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Composable
fun WaitingRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    leftOutFirst: Boolean = false,
    viewModel: WaitingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = stringResource(R.string.producer_waiting_undo)
    LaunchedEffect(viewModel, resources) {
        viewModel.changes.collectLatest { change ->
            val plural = if (change.leftOut) {
                R.plurals.producer_waiting_left_out_done
            } else {
                R.plurals.producer_waiting_synced_done
            }
            val message = resources.getQuantityString(plural, change.count, change.count)
            val result = snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }
    val noViewer = stringResource(R.string.producer_waiting_no_viewer)
    WaitingScreen(
        state = state,
        onDone = onDone,
        modifier = modifier,
        startWith = if (leftOutFirst) WaitingList.LEFT_OUT else WaitingList.TO_SYNC,
        snackbars = snackbars,
        onToggle = viewModel::toggle,
        onSelect = viewModel::select,
        onClearSelection = viewModel::clearSelection,
        onMove = viewModel::moveSelected,
        onOpen = { media ->
            if (!context.open(media)) scope.launch { snackbars.showSnackbar(noViewer) }
        },
    )
}

/** Opens [media] in the phone's gallery or player; `false` if no app can. */
private fun Context.open(media: WaitingMedia): Boolean {
    val uri = media.uri.toUri()
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false // A shared file's copy: only this app reads it.
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, if (media.video) "video/*" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * The photos and videos waiting to sync, as a gallery: [startWith] the list to sync, or the one left out. Tapping one
 * opens it; touching and holding one starts choosing, and the chosen ones move to the other list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // One callback each.
fun WaitingScreen(
    state: WaitingUiState,
    onDone: () -> Unit,
    onToggle: (String) -> Unit,
    onSelect: (Collection<String>, Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onMove: (WaitingList) -> Unit,
    onOpen: (WaitingMedia) -> Unit,
    modifier: Modifier = Modifier,
    startWith: WaitingList = WaitingList.TO_SYNC,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    var list by rememberSaveable { mutableStateOf(startWith) }
    val ready = state as? WaitingUiState.Ready
    val shown = when (list) {
        WaitingList.TO_SYNC -> ready?.toSync
        WaitingList.LEFT_OUT -> ready?.leftOut
    }.orEmpty()
    val chosen = ready?.selected.orEmpty().let { selected -> shown.filter { it.key in selected } }
    val choosing = chosen.isNotEmpty()
    BackHandler(enabled = choosing, onBack = onClearSelection)
    Scaffold(
        modifier = modifier,
        topBar = { TopBar(list, chosen.size, onDone, onClearSelection, onMove) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ready == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            Lists(list, ready) { next ->
                onClearSelection()
                list = next
            }
            Gallery(list, shown, ready.selected, choosing, onToggle, onSelect, onOpen)
        }
    }
}

/** The screen's title, or while choosing, how many are chosen and what to do with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    list: WaitingList,
    chosen: Int,
    onDone: () -> Unit,
    onClearSelection: () -> Unit,
    onMove: (WaitingList) -> Unit,
) {
    if (chosen == 0) {
        TopAppBar(
            title = { Text(stringResource(R.string.producer_waiting_title)) },
            actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.producer_folders_done)) } },
        )
        return
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(
                    painter = painterResource(MediaSyncIcons.Close),
                    contentDescription = stringResource(R.string.producer_waiting_clear_selection),
                )
            }
        },
        title = { Text(pluralStringResource(R.plurals.producer_waiting_chosen, chosen, chosen)) },
        actions = {
            TextButton(onClick = { onMove(list) }) {
                Text(
                    stringResource(
                        when (list) {
                            WaitingList.TO_SYNC -> R.string.producer_waiting_leave_out
                            WaitingList.LEFT_OUT -> R.string.producer_waiting_bring_back
                        },
                    ),
                )
            }
        },
    )
}

/** The two lists, to switch between, with how many each holds. */
@Composable
private fun Lists(list: WaitingList, state: WaitingUiState.Ready, onShow: (WaitingList) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = list == WaitingList.TO_SYNC,
            onClick = { onShow(WaitingList.TO_SYNC) },
            label = { Text(stringResource(R.string.producer_waiting_to_sync, state.toSync.size)) },
        )
        FilterChip(
            selected = list == WaitingList.LEFT_OUT,
            onClick = { onShow(WaitingList.LEFT_OUT) },
            label = { Text(stringResource(R.string.producer_waiting_left_out, state.leftOut.size)) },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun Gallery(
    list: WaitingList,
    shown: List<WaitingMedia>,
    selected: Set<String>,
    choosing: Boolean,
    onToggle: (String) -> Unit,
    onSelect: (Collection<String>, Boolean) -> Unit,
    onOpen: (WaitingMedia) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val days = remember(shown) { shown.groupBy { Instant.ofEpochMilli(it.takenAtMillis).atZone(zone).toLocalDate() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_MIN_SIZE),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(
                    when {
                        shown.isEmpty() && list == WaitingList.TO_SYNC -> R.string.producer_waiting_to_sync_empty
                        shown.isEmpty() -> R.string.producer_waiting_left_out_empty
                        list == WaitingList.TO_SYNC -> R.string.producer_waiting_to_sync_hint
                        else -> R.string.producer_waiting_left_out_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        days.forEach { (day, media) ->
            item(key = "day $day", span = { GridItemSpan(maxLineSpan) }) {
                DayHeader(day, choosing, all = media.all { it.key in selected }) { chosen ->
                    onSelect(media.map { it.key }, chosen)
                }
            }
            items(media, key = { it.key }) { item ->
                Tile(item, choosing, selected = item.key in selected, onToggle = { onToggle(item.key) }) {
                    onOpen(item)
                }
            }
        }
    }
}

/** A day's heading; while choosing, it chooses the whole day. */
@Composable
private fun DayHeader(day: LocalDate, choosing: Boolean, all: Boolean, onChoose: (Boolean) -> Unit) {
    val name = dayName(day)
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .padding(horizontal = 16.dp)
    Row(
        modifier = if (choosing) {
            modifier.toggleable(value = all, role = Role.Checkbox, onValueChange = onChoose)
        } else {
            modifier
        }.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (choosing) SelectionMark(all, onImage = false)
        Text(name, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun dayName(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.producer_waiting_today)

        today.minusDays(1) -> stringResource(R.string.producer_waiting_yesterday)

        else -> {
            val locale = LocalConfiguration.current.locales[0]
            val skeleton = if (day.year == today.year) "EEEMMMd" else "EEEMMMdyyyy"
            day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
        }
    }
}

/** One photo or video: its thumbnail, marked as a video, and while choosing, whether it's chosen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(media: WaitingMedia, choosing: Boolean, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val taken = Instant.ofEpochMilli(media.takenAtMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    val description = stringResource(
        if (media.video) R.string.producer_waiting_video else R.string.producer_waiting_photo,
        taken,
    )
    val inset by animateDpAsState(if (selected) SELECTED_INSET else 0.dp, label = "inset")
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClickLabel = if (choosing) null else stringResource(R.string.producer_waiting_open),
                onLongClickLabel = stringResource(R.string.producer_waiting_choose),
                onLongClick = onToggle,
                onClick = if (choosing) onToggle else onOpen,
            )
            .semantics {
                contentDescription = description
                if (choosing) this.selected = selected
            },
    ) {
        Thumbnail(
            media,
            Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(RoundedCornerShape(if (selected) 12.dp else 0.dp)),
        )
        if (media.video) {
            Icon(
                painter = painterResource(MediaSyncIcons.Video),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(SCRIM, CircleShape)
                    .padding(2.dp)
                    .size(18.dp),
            )
        }
        if (choosing) SelectionMark(selected, onImage = true, Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

/** Whether a photo, a video or a whole day is chosen; [onImage], drawn over a thumbnail. */
@Composable
private fun SelectionMark(selected: Boolean, onImage: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Icon(
            painter = painterResource(MediaSyncIcons.Selected),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.background(MaterialTheme.colorScheme.surface, CircleShape).size(24.dp),
        )
    } else {
        Icon(
            painter = painterResource(MediaSyncIcons.NotSelected),
            contentDescription = null,
            tint = if (onImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = (if (onImage) modifier.background(SCRIM, CircleShape) else modifier).size(24.dp),
        )
    }
}

@Composable
private fun Thumbnail(media: WaitingMedia, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState(Thumbnails.cached(media.uri), media.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbnails.load(context, media) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Thumbnails already shown, so scrolling back doesn't read them again. */
private object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / MEMORY_SHARE).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(uri: String): ImageBitmap? = cache.get(uri)?.asImageBitmap()

    /** `null` if it can't be read: gone, or no longer allowed. */
    fun load(context: Context, media: WaitingMedia): ImageBitmap? {
        val uri = media.uri.toUri()
        val size = Size(THUMBNAIL_PX, THUMBNAIL_PX)
        val bitmap = try {
            when {
                // A shared file's copy isn't in the gallery: read from the file itself.
                uri.scheme == ContentResolver.SCHEME_FILE && media.video ->
                    ThumbnailUtils.createVideoThumbnail(File(uri.path!!), size, null)

                uri.scheme == ContentResolver.SCHEME_FILE -> ThumbnailUtils.createImageThumbnail(
                    File(uri.path!!),
                    size,
                    null,
                )

                else -> context.contentResolver.loadThumbnail(uri, size, null)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return null
        cache.put(media.uri, bitmap)
        return bitmap.asImageBitmap()
    }

    /** Up to an eighth of the app's memory. */
    private const val MEMORY_SHARE = 8
    private const val THUMBNAIL_PX = 256
}

private val TILE_MIN_SIZE = 96.dp
private val SELECTED_INSET = 10.dp
private val SCRIM = Color.Black.copy(alpha = 0.35f)

@Preview
@Composable
private fun WaitingScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        WaitingScreen(
            state = WaitingUiState.Ready(
                toSync = List(7) { WaitingMedia("$it", "content://media/$it", video = it == 2, 1_761_800_000_000) },
                leftOut = emptyList(),
                selected = setOf("1"),
            ),
            onDone = {},
            onToggle = {},
            onSelect = { _, _ -> },
            onClearSelection = {},
            onMove = {},
            onOpen = {},
        )
    }
}
