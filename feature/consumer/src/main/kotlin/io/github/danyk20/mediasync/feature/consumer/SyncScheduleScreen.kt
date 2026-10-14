/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import io.github.danyk20.mediasync.core.designsystem.component.formatTime
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SyncSchedule
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The choices on the schedule screen, one per kind of [SyncSchedule]. */
enum class ScheduleOption {
    DAILY,
    EVERY_HOURS,
    EVERY_DAYS,
    WEEKLY,
    WHILE_CHARGING,
    MANUAL,
    ;

    companion object {
        fun of(schedule: SyncSchedule) = when (schedule) {
            is SyncSchedule.Daily -> DAILY
            is SyncSchedule.EveryHours -> EVERY_HOURS
            is SyncSchedule.EveryDays -> EVERY_DAYS
            is SyncSchedule.Weekly -> WEEKLY
            SyncSchedule.WhileCharging -> WHILE_CHARGING
            SyncSchedule.Manual -> MANUAL
        }
    }
}

/** The receiving phone's sync schedule, which its sending phones follow (docs/PLAN.md §10.1). Saved at once. */
@HiltViewModel
class SyncScheduleViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {

    /** `null` while loading. */
    val schedule: StateFlow<SyncSchedule?> = settings.syncSchedule.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Switches to [option], keeping the time and interval already chosen where they apply. */
    fun choose(option: ScheduleOption) {
        val current = schedule.value ?: SyncSchedule.Default
        val time = current.time() ?: DEFAULT_TIME
        save(
            when (option) {
                ScheduleOption.DAILY -> SyncSchedule.Daily(time)
                ScheduleOption.EVERY_HOURS -> SyncSchedule.EveryHours(DEFAULT_HOURS, time)
                ScheduleOption.EVERY_DAYS -> SyncSchedule.EveryDays(SyncSchedule.DAYS.first(), time)
                ScheduleOption.WEEKLY -> SyncSchedule.Weekly(DayOfWeek.SUNDAY, time)
                ScheduleOption.WHILE_CHARGING -> SyncSchedule.WhileCharging
                ScheduleOption.MANUAL -> SyncSchedule.Manual
            }.takeUnless { ScheduleOption.of(current) == option } ?: current,
        )
    }

    fun setTime(time: LocalTime) = update {
        when (it) {
            is SyncSchedule.Daily -> it.copy(at = time)
            is SyncSchedule.EveryHours -> it.copy(from = time)
            is SyncSchedule.EveryDays -> it.copy(at = time)
            is SyncSchedule.Weekly -> it.copy(at = time)
            else -> it
        }
    }

    fun setHours(hours: Int) = update { (it as? SyncSchedule.EveryHours)?.copy(hours = hours) ?: it }

    fun setDays(days: Int) = update { (it as? SyncSchedule.EveryDays)?.copy(days = days) ?: it }

    fun setDay(day: DayOfWeek) = update { (it as? SyncSchedule.Weekly)?.copy(day = day) ?: it }

    private fun update(change: (SyncSchedule) -> SyncSchedule) = save(change(schedule.value ?: SyncSchedule.Default))

    private fun save(schedule: SyncSchedule) {
        viewModelScope.launch { settings.setSyncSchedule(schedule) }
    }

    private companion object {
        val DEFAULT_TIME: LocalTime = LocalTime.of(3, 0)
        const val DEFAULT_HOURS = 6
    }
}

/** The time of day a timed schedule runs at (or starts counting from), `null` for the others. */
internal fun SyncSchedule.time(): LocalTime? = when (this) {
    is SyncSchedule.Daily -> at
    is SyncSchedule.EveryHours -> from
    is SyncSchedule.EveryDays -> at
    is SyncSchedule.Weekly -> at
    SyncSchedule.WhileCharging, SyncSchedule.Manual -> null
}

@Composable
fun SyncScheduleRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncScheduleViewModel = hiltViewModel(),
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    SyncScheduleScreen(
        schedule = schedule,
        actions = ScheduleActions(
            choose = viewModel::choose,
            setTime = viewModel::setTime,
            setHours = viewModel::setHours,
            setDays = viewModel::setDays,
            setDay = viewModel::setDay,
        ),
        onDone = onDone,
        modifier = modifier,
    )
}

/** What the user can change on the schedule screen. */
class ScheduleActions(
    val choose: (ScheduleOption) -> Unit = {},
    val setTime: (LocalTime) -> Unit = {},
    val setHours: (Int) -> Unit = {},
    val setDays: (Int) -> Unit = {},
    val setDay: (DayOfWeek) -> Unit = {},
)

/** When the sending phones sync with this phone. [schedule] `null`: still loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScheduleScreen(
    schedule: SyncSchedule?,
    actions: ScheduleActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.consumer_schedule_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.consumer_schedule_done)) } },
            )
        },
    ) { padding ->
        if (schedule == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Explanation(stringResource(R.string.consumer_schedule_intro))
            Column(Modifier.selectableGroup()) {
                ScheduleOption.entries.forEach { option ->
                    OptionRow(option, selected = ScheduleOption.of(schedule) == option, onSelect = {
                        actions.choose(option)
                    })
                    if (ScheduleOption.of(schedule) == option) Details(schedule, actions)
                }
            }
            Explanation(
                stringResource(
                    if (schedule is SyncSchedule.Timed) {
                        R.string.consumer_schedule_window_hint
                    } else {
                        R.string.consumer_schedule_open_hint
                    },
                ),
            )
        }
    }
}

@Composable
private fun OptionRow(option: ScheduleOption, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(option.title), style = MaterialTheme.typography.bodyLarge)
            option.description?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The settings of the chosen option: interval, weekday and time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(schedule: SyncSchedule, actions: ScheduleActions) {
    val time = schedule.time() ?: return
    Column(
        modifier = Modifier.padding(start = DETAILS_INDENT, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (schedule) {
                is SyncSchedule.EveryHours -> SyncSchedule.HOURS.forEach { hours ->
                    FilterChip(
                        selected = schedule.hours == hours,
                        onClick = { actions.setHours(hours) },
                        label = { Text(pluralStringResource(R.plurals.consumer_schedule_hours, hours, hours)) },
                    )
                }

                is SyncSchedule.EveryDays -> SyncSchedule.DAYS.forEach { days ->
                    FilterChip(
                        selected = schedule.days == days,
                        onClick = { actions.setDays(days) },
                        label = { Text(pluralStringResource(R.plurals.consumer_schedule_days, days, days)) },
                    )
                }

                is SyncSchedule.Weekly -> DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = schedule.day == day,
                        onClick = { actions.setDay(day) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0])) },
                    )
                }

                else -> Unit
            }
        }
        val label = if (schedule is SyncSchedule.EveryHours) {
            R.string.consumer_schedule_from
        } else {
            R.string.consumer_schedule_at
        }
        TimeButton(
            label = stringResource(label, formatTime(time)),
            time = time,
            onTime = actions.setTime,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(label: String, time: LocalTime, onTime: (LocalTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }) { Text(label) }
    if (!picking) return
    val state = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = { picking = false },
        confirmButton = {
            TextButton(
                onClick = {
                    onTime(LocalTime.of(state.hour, state.minute))
                    picking = false
                },
            ) { Text(stringResource(R.string.consumer_schedule_time_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { picking = false }) { Text(stringResource(R.string.consumer_schedule_time_cancel)) }
        },
        text = { TimePicker(state = state) },
    )
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

private val ScheduleOption.title: Int
    get() = when (this) {
        ScheduleOption.DAILY -> R.string.consumer_schedule_daily
        ScheduleOption.EVERY_HOURS -> R.string.consumer_schedule_every_hours
        ScheduleOption.EVERY_DAYS -> R.string.consumer_schedule_every_days
        ScheduleOption.WEEKLY -> R.string.consumer_schedule_weekly
        ScheduleOption.WHILE_CHARGING -> R.string.consumer_schedule_while_charging
        ScheduleOption.MANUAL -> R.string.consumer_schedule_manual
    }

private val ScheduleOption.description: Int?
    get() = when (this) {
        ScheduleOption.WHILE_CHARGING -> R.string.consumer_schedule_while_charging_description
        ScheduleOption.MANUAL -> R.string.consumer_schedule_manual_description
        else -> null
    }

private val DETAILS_INDENT = 72.dp

@Preview
@Composable
private fun SyncScheduleScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        SyncScheduleScreen(
            schedule = SyncSchedule.EveryHours(6, LocalTime.of(3, 0)),
            actions = ScheduleActions(),
            onDone = {},
        )
    }
}
