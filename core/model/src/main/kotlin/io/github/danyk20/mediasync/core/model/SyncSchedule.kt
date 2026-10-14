/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * When scheduled syncs happen (docs/PLAN.md §10.1). The receiving phone owns the schedule and tells its sending
 * phones in every session, so the whole household follows one. Sending phones sync at the due times while
 * charging; one that isn't charging then catches up at its next charge.
 *
 * Times are local: a due time follows the phone's time zone and daylight saving. A time that daylight saving
 * skips runs at the first valid moment after it; a time that occurs twice runs once.
 */
sealed interface SyncSchedule {

    /** The latest due moment at or before [now]; `null` if this schedule has no due times. */
    fun previousDue(now: ZonedDateTime): ZonedDateTime?

    /** The first due moment after [now]; `null` if this schedule has no due times. */
    fun nextDue(now: ZonedDateTime): ZonedDateTime?

    /** Compact text for storage and the wire, read back by [decode]. */
    fun encode(): String

    /** Due times at fixed moments, the same set for every day, week or run of days. */
    sealed class Timed : SyncSchedule {
        /** The local due times on [date], earliest first. */
        internal abstract fun timesOn(date: LocalDate): List<LocalTime>

        override fun previousDue(now: ZonedDateTime): ZonedDateTime? = (0..LOOKAROUND_DAYS).asSequence()
            .map { now.toLocalDate().minusDays(it.toLong()) }
            .flatMap { date -> timesOn(date).asReversed().asSequence().map { at(date, it, now.zone) } }
            .firstOrNull { !it.isAfter(now) }

        override fun nextDue(now: ZonedDateTime): ZonedDateTime? = (-1..LOOKAROUND_DAYS).asSequence()
            .map { now.toLocalDate().plusDays(it.toLong()) }
            .flatMap { date -> timesOn(date).asSequence().map { at(date, it, now.zone) } }
            .firstOrNull { it.isAfter(now) }
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

        override fun timesOn(date: LocalDate) =
            (0 until HOURS_PER_DAY / hours).map { from.plusHours((it * hours).toLong()) }.sorted()

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

        override fun timesOn(date: LocalDate) = if (Math.floorMod(date.toEpochDay(), days.toLong()) == 0L) {
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
        override fun previousDue(now: ZonedDateTime): ZonedDateTime? = null

        override fun nextDue(now: ZonedDateTime): ZonedDateTime? = null

        override fun encode() = "charging"
    }

    /** Only *Sync now*. */
    data object Manual : SyncSchedule {
        override fun previousDue(now: ZonedDateTime): ZonedDateTime? = null

        override fun nextDue(now: ZonedDateTime): ZonedDateTime? = null

        override fun encode() = "manual"
    }

    companion object {
        val Default: SyncSchedule = Daily(LocalTime.of(3, 0))

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
        } catch (_: DateTimeParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null // A bad number or day name, or an interval this version doesn't offer.
        }

        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private const val HOURS_PER_DAY = 24

        /** Far enough to reach any due time: weekly is the longest gap. */
        private const val LOOKAROUND_DAYS = 8
    }
}

/**
 * [time] on [date] in [zone]. A time daylight saving skips becomes the moment the clocks jump; a time that
 * occurs twice uses its first occurrence, so it runs once.
 */
private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
    val local = LocalDateTime.of(date, time)
    val offsets = zone.rules.getValidOffsets(local)
    return if (offsets.isEmpty()) {
        zone.rules.getTransition(local).instant.atZone(zone)
    } else {
        ZonedDateTime.ofLocal(local, zone, offsets.first())
    }
}
