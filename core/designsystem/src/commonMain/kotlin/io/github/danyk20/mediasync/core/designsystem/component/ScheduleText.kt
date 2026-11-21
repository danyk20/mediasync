/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.moment_today
import io.github.danyk20.mediasync.core.designsystem.generated.resources.moment_tomorrow
import io.github.danyk20.mediasync.core.designsystem.generated.resources.moment_weekday
import io.github.danyk20.mediasync.core.designsystem.generated.resources.moment_yesterday
import io.github.danyk20.mediasync.core.designsystem.generated.resources.schedule_daily
import io.github.danyk20.mediasync.core.designsystem.generated.resources.schedule_every_days
import io.github.danyk20.mediasync.core.designsystem.generated.resources.schedule_every_hours
import io.github.danyk20.mediasync.core.designsystem.generated.resources.schedule_manual
import io.github.danyk20.mediasync.core.designsystem.generated.resources.schedule_weekly
import io.github.danyk20.mediasync.core.designsystem.generated.resources.schedule_while_charging
import io.github.danyk20.mediasync.core.model.SyncSchedule
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A time of day the way the phone shows times: "3:00 AM" or "03:00", following the user's 12/24-hour choice. */
@Composable
fun formatTime(time: LocalTime): String = formatTimeOfDay(time)

/** "Every day at 3:00 AM", "Every 6 hours from 3:00 AM", "Whenever charging"… */
@Composable
fun scheduleSummary(schedule: SyncSchedule): String = when (schedule) {
    is SyncSchedule.Daily -> stringResource(Res.string.schedule_daily, formatTime(schedule.at))

    is SyncSchedule.EveryHours ->
        pluralStringResource(
            Res.plurals.schedule_every_hours,
            schedule.hours,
            schedule.hours,
            formatTime(schedule.from),
        )

    is SyncSchedule.EveryDays ->
        pluralStringResource(Res.plurals.schedule_every_days, schedule.days, schedule.days, formatTime(schedule.at))

    is SyncSchedule.Weekly -> stringResource(
        Res.string.schedule_weekly,
        weekdayName(schedule.day),
        formatTime(schedule.at),
    )

    SyncSchedule.WhileCharging -> stringResource(Res.string.schedule_while_charging)

    SyncSchedule.Manual -> stringResource(Res.string.schedule_manual)
}

/**
 * "today at 9:00 PM", "tomorrow at 3:00 AM", "yesterday at 3:00 AM", or "Monday at 3:00 AM" for another
 * [moment] within a week of [now], all in [zone] (the phone's).
 */
@Composable
fun relativeMoment(moment: Instant, now: Instant, zone: TimeZone): String {
    val local = moment.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val time = formatTime(local.time)
    return when (local.date) {
        today -> stringResource(Res.string.moment_today, time)
        today.plus(1, DateTimeUnit.DAY) -> stringResource(Res.string.moment_tomorrow, time)
        today.minus(1, DateTimeUnit.DAY) -> stringResource(Res.string.moment_yesterday, time)
        else -> stringResource(Res.string.moment_weekday, weekdayName(local.date.dayOfWeek), time)
    }
}

/** A past [moment] as [relativeMoment] says it within the last week, else its date: "Oct 2, 2026". */
@Composable
fun pastMoment(moment: Instant, now: Instant, zone: TimeZone): String {
    if (isAfterDaysBefore(moment, now, zone, RECENT_DAYS)) return relativeMoment(moment, now, zone)
    return formatMediumDate(moment)
}

private const val RECENT_DAYS = 6
