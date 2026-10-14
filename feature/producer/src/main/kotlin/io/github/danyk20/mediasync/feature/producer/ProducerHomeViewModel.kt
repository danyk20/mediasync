/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProducerHomeUiState(
    /** `null` until a receiving phone is paired. */
    val consumerName: String? = null,
    val canReadLibrary: Boolean = false,
    val canReadLocation: Boolean = false,
    val sync: ProducerState = ProducerState.Idle(),
    /** Photos and videos not yet sent; `null` while counting. */
    val pendingCount: Int? = null,
    val sources: SourceSelection = SourceSelection.Default,
    /** The receiving phone's schedule, which this phone follows; `null` until paired. */
    val schedule: SyncSchedule? = null,
    /** When the next scheduled sync is due, as of [now]; `null` without a timed schedule. */
    val nextSyncAt: ZonedDateTime? = null,
    val now: ZonedDateTime? = null,
)

@HiltViewModel
class ProducerHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ProducerSync,
    peers: PeerStore,
    library: MediaLibrary,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val permissions = MutableStateFlow(currentPermissions())
    private val pending = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<ProducerHomeUiState> = combine(
        peers.observeConsumer(),
        permissions,
        sync.state,
        pending,
        settings.sourceSelection,
    ) { consumer, (library, location), state, count, sources ->
        val schedule = consumer?.let { it.schedule ?: SyncSchedule.Default }
        val now = ZonedDateTime.now(clock)
        ProducerHomeUiState(
            consumerName = consumer?.name,
            canReadLibrary = library,
            canReadLocation = location,
            sync = state,
            pendingCount = count,
            sources = sources,
            schedule = schedule,
            nextSyncAt = schedule?.nextDue(now),
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProducerHomeUiState())

    init {
        // Recount whenever a sync ends, the receiving phone or the chosen folders change, or photos are added,
        // edited or deleted. A newer reason cancels a count still running.
        @OptIn(FlowPreview::class)
        val reasons = merge(
            uiState.distinctUntilChangedBy {
                listOf(it.consumerName, it.canReadLibrary, it.sync is ProducerState.Idle, it.sources)
            }
                .map { },
            library.changes().debounce(LIBRARY_SETTLE_MILLIS),
        )
        viewModelScope.launch { reasons.collectLatest { countPending() } }
    }

    fun syncNow() = sync.syncNow()

    fun stop() = sync.cancel()

    /** Call after the user answered a permission prompt or came back from settings. */
    fun onPermissionsChanged() {
        permissions.value = currentPermissions()
    }

    private suspend fun countPending() {
        val state = uiState.value
        if (state.consumerName == null || !state.canReadLibrary || state.sync !is ProducerState.Idle) return
        pending.value = sync.pendingCount()
    }

    private fun currentPermissions() =
        MediaPermissions.canReadLibrary(context) to MediaPermissions.canReadLocation(context)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
