/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * When scheduled syncs happen (docs/PLAN.md §10.1). The receiving phone owns the schedule and tells its sending
 * phones in every session, so the whole household follows one. Sending phones sync at the due times while
 * charging; one that isn't charging then catches up at its next charge.
 *
 * Times are local: a due time follows the phone's time zone and daylight saving. A time that daylight saving
 * skips runs at the first valid moment after it; a time that occurs twice runs once.
 */
sealed interface SyncSchedule {

    /** The latest due moment at or before [now] in [zone]; `null` if this schedule has no due times. */
    fun previousDue(now: Instant, zone: TimeZone): Instant?

    /** The first due moment after [now] in [zone]; `null` if this schedule has no due times. */
    fun nextDue(now: Instant, zone: TimeZone): Instant?

    /** Compact text for storage and the wire, read back by [decode]. */
    fun encode(): String

    /** Due times at fixed moments, the same set for every day, week or run of days. */
    sealed class Timed : SyncSchedule {
        /** The local due times on [date], earliest first. */
        internal abstract fun timesOn(date: LocalDate): List<LocalTime>

        override fun previousDue(now: Instant, zone: TimeZone): Instant? {
            val today = now.toLocalDateTime(zone).date
            return (0..LOOKAROUND_DAYS).asSequence()
                .map { today.minus(it, DateTimeUnit.DAY) }
                .flatMap { date -> timesOn(date).asReversed().asSequence().map { at(date, it, zone) } }
                .firstOrNull { it <= now }
        }

        override fun nextDue(now: Instant, zone: TimeZone): Instant? {
            val today = now.toLocalDateTime(zone).date
            return (-1..LOOKAROUND_DAYS).asSequence()
                .map { today.plus(it, DateTimeUnit.DAY) }
                .flatMap { date -> timesOn(date).asSequence().map { at(date, it, zone) } }
                .firstOrNull { it > now }
        }
    }

    /** Once a day at [at]. The default, at 03:00. */
    data class Daily(val at: LocalTime) : Timed() {
        override fun timesOn(date: LocalDate) = listOf(at)

        override fun encode() = "daily@${at.format(TIME)}"
    }

    /** Every [hours] hours, counted from [from]: every 6 hours from 03:00 is 03:00, 09:00, 15:00 and 21:00. */
    data class EveryHours(val hours: Int, val from: LocalTime) : Timed() {
        init {
            require(hours in HOURS) { "Every $hours hours isn't offered" }
        }

        override fun timesOn(date: LocalDate) = (0 until HOURS_PER_DAY / hours)
            .map { LocalTime.fromSecondOfDay((from.toSecondOfDay() + it * hours * SECONDS_PER_HOUR) % SECONDS_PER_DAY) }
            .sorted()

        override fun encode() = "hours:$hours@${from.format(TIME)}"
    }

    /**
     * Every [days] days at [at]. Due days are counted from 1 January 1970, so every phone agrees on them
     * without having to know when the schedule was chosen.
     */
    data class EveryDays(val days: Int, val at: LocalTime) : Timed() {
        init {
            require(days in DAYS) { "Every $days days isn't offered" }
        }

        override fun timesOn(date: LocalDate) = if (date.toEpochDays().mod(days.toLong()) == 0L) {
            listOf(at)
        } else {
            emptyList()
        }

        override fun encode() = "days:$days@${at.format(TIME)}"
    }

    /** Once a week, on [day] at [at]. */
    data class Weekly(val day: DayOfWeek, val at: LocalTime) : Timed() {
        override fun timesOn(date: LocalDate) = if (date.dayOfWeek == day) listOf(at) else emptyList()

        override fun encode() = "weekly:${day.name}@${at.format(TIME)}"
    }

    /**
     * Sending phones sync whenever they're charging and have new photos; the receiving phone listens while
     * charging.
     */
    data object WhileCharging : SyncSchedule {
        override fun previousDue(now: Instant, zone: TimeZone): Instant? = null

        override fun nextDue(now: Instant, zone: TimeZone): Instant? = null

        override fun encode() = "charging"
    }

    /** Only *Sync now*. */
    data object Manual : SyncSchedule {
        override fun previousDue(now: Instant, zone: TimeZone): Instant? = null

        override fun nextDue(now: Instant, zone: TimeZone): Instant? = null

        override fun encode() = "manual"
    }

    companion object {
        val Default: SyncSchedule = Daily(LocalTime(3, 0))

        /** The intervals offered for [EveryHours]: each divides a day evenly. */
        val HOURS = listOf(2, 4, 6, 12)

        /** The intervals offered for [EveryDays]. */
        val DAYS = listOf(2, 3)

        /** Reads [encode]'s text; `null` if it's malformed or from a newer version this one doesn't know. */
        fun decode(text: String): SyncSchedule? = try {
            val kind = text.substringBefore('@')
            val time = text.substringAfter('@', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
                ?.let { LocalTime.parse(it, TIME) }
            when {
                text == "charging" -> WhileCharging
                text == "manual" -> Manual
                time == null -> null
                kind == "daily" -> Daily(time)
                kind.startsWith("hours:") -> EveryHours(kind.removePrefix("hours:").toInt(), time)
                kind.startsWith("days:") -> EveryDays(kind.removePrefix("days:").toInt(), time)
                kind.startsWith("weekly:") -> Weekly(DayOfWeek.valueOf(kind.removePrefix("weekly:")), time)
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            // An unreadable time (DateTimeFormatException is one), a bad number or day name, or an interval this
            // version doesn't offer.
            null
        }

        private val TIME = LocalTime.Format {
            hour()
            char(':')
            minute()
        }
        private const val HOURS_PER_DAY = 24
        private const val SECONDS_PER_HOUR = 3600
        private const val SECONDS_PER_DAY = HOURS_PER_DAY * SECONDS_PER_HOUR

        /** Far enough to reach any due time: weekly is the longest gap. */
        private const val LOOKAROUND_DAYS = 8
    }
}

/**
 * [time] on [date] in [zone]. A time daylight saving skips becomes the moment the clocks jump; a time that
 * occurs twice uses its first occurrence, so it runs once.
 */
private fun at(date: LocalDate, time: LocalTime, zone: TimeZone): Instant {
    val local = LocalDateTime(date, time)
    val instant = local.toInstant(zone)
    if (instant.toLocalDateTime(zone) == local) return instant
    // Skipped by daylight saving: the instant returned is past the jump. Walk back to the jump itself, the first
    // moment whose local time is after [local]. Clocks jump on whole minutes.
    var jump = instant
    while ((jump - 1.minutes).toLocalDateTime(zone) > local) jump -= 1.minutes
    return jump
}
