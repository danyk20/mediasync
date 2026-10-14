/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.danyk20.mediasync.core.designsystem.R
import io.github.danyk20.mediasync.core.model.SyncSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Date

/** A time of day the way the phone shows times: "3:00 AM" or "03:00", following the user's 12/24-hour choice. */
@Composable
fun formatTime(time: LocalTime): String {
    val context = LocalContext.current
    LocalConfiguration.current // Recompose when the locale or the 24-hour setting changes.
    val today = LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant()
    return DateFormat.getTimeFormat(context).format(Date.from(today))
}

/** "Every day at 3:00 AM", "Every 6 hours from 3:00 AM", "Whenever charging"… */
@Composable
fun scheduleSummary(schedule: SyncSchedule): String = when (schedule) {
    is SyncSchedule.Daily -> stringResource(R.string.schedule_daily, formatTime(schedule.at))

    is SyncSchedule.EveryHours ->
        pluralStringResource(R.plurals.schedule_every_hours, schedule.hours, schedule.hours, formatTime(schedule.from))

    is SyncSchedule.EveryDays ->
        pluralStringResource(R.plurals.schedule_every_days, schedule.days, schedule.days, formatTime(schedule.at))

    is SyncSchedule.Weekly -> stringResource(R.string.schedule_weekly, dayName(schedule.day), formatTime(schedule.at))

    SyncSchedule.WhileCharging -> stringResource(R.string.schedule_while_charging)

    SyncSchedule.Manual -> stringResource(R.string.schedule_manual)
}

/** "today at 9:00 PM", "tomorrow at 3:00 AM", or "Monday at 3:00 AM" for a [moment] within the coming week. */
@Composable
fun relativeMoment(moment: ZonedDateTime, now: ZonedDateTime): String {
    val local = moment.withZoneSameInstant(now.zone)
    val time = formatTime(local.toLocalTime())
    return when (local.toLocalDate()) {
        now.toLocalDate() -> stringResource(R.string.moment_today, time)
        now.toLocalDate().plusDays(1) -> stringResource(R.string.moment_tomorrow, time)
        else -> stringResource(R.string.moment_weekday, dayName(local.dayOfWeek), time)
    }
}

@Composable
private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])
