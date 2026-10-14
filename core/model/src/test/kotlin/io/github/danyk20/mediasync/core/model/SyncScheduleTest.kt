/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SyncScheduleTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    private fun at(text: String) = ZonedDateTime.parse("$text[Europe/Zurich]")

    private val threeAm = LocalTime.of(3, 0)

    @Test
    fun theDefaultIsDailyAtThreeInTheMorning() {
        SyncSchedule.Default shouldBe SyncSchedule.Daily(threeAm)
    }

    @Test
    fun dailyIsDueAtItsTimeEachDay() {
        val daily = SyncSchedule.Daily(threeAm)

        daily.nextDue(at("2026-10-14T21:30+02:00")) shouldBe at("2026-10-15T03:00+02:00")
        daily.nextDue(at("2026-10-15T03:00+02:00")) shouldBe at("2026-10-16T03:00+02:00")
        daily.previousDue(at("2026-10-15T03:00+02:00")) shouldBe at("2026-10-15T03:00+02:00")
        daily.previousDue(at("2026-10-15T02:59+02:00")) shouldBe at("2026-10-14T03:00+02:00")
    }

    @Test
    fun everyFewHoursIsCountedFromItsStartTimeAndWrapsPastMidnight() {
        val everySix = SyncSchedule.EveryHours(6, LocalTime.of(21, 0))

        everySix.nextDue(at("2026-10-14T21:30+02:00")) shouldBe at("2026-10-15T03:00+02:00")
        everySix.nextDue(at("2026-10-15T03:00+02:00")) shouldBe at("2026-10-15T09:00+02:00")
        everySix.previousDue(at("2026-10-15T08:59+02:00")) shouldBe at("2026-10-15T03:00+02:00")
    }

    @Test
    fun everyFewDaysFallsOnTheSameDaysForEveryPhone() {
        val everyTwo = SyncSchedule.EveryDays(2, threeAm)
        // 2026-10-16 is day 20742 since 1970-01-01, an even number.
        everyTwo.nextDue(at("2026-10-14T12:00+02:00")) shouldBe at("2026-10-16T03:00+02:00")
        everyTwo.nextDue(at("2026-10-16T12:00+02:00")) shouldBe at("2026-10-18T03:00+02:00")
    }

    @Test
    fun weeklyIsDueOnItsDay() {
        val sundays = SyncSchedule.Weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0))

        sundays.nextDue(at("2026-10-14T12:00+02:00")) shouldBe at("2026-10-18T20:00+02:00")
        sundays.previousDue(at("2026-10-14T12:00+02:00")) shouldBe at("2026-10-11T20:00+02:00")
    }

    @Test
    fun aTimeSkippedBySpringDaylightSavingRunsWhenTheClocksJump() {
        // On 2026-03-29 Zurich jumps from 02:00 to 03:00; 02:30 doesn't exist that night.
        SyncSchedule.Daily(LocalTime.of(2, 30)).nextDue(at("2026-03-28T12:00+01:00")) shouldBe
            at("2026-03-29T03:00+02:00")
        // 03:00 itself exists on both switch days, so the default never moves.
        SyncSchedule.Default.nextDue(at("2026-03-28T12:00+01:00")) shouldBe at("2026-03-29T03:00+02:00")
        SyncSchedule.Default.nextDue(at("2026-10-24T12:00+02:00")) shouldBe at("2026-10-25T03:00+01:00")
    }

    @Test
    fun aTimeRepeatedByAutumnDaylightSavingRunsOnce() {
        // On 2026-10-25 Zurich goes back from 03:00 to 02:00; 02:30 happens twice.
        val daily = SyncSchedule.Daily(LocalTime.of(2, 30))
        val first = daily.nextDue(at("2026-10-24T12:00+02:00"))

        first shouldBe at("2026-10-25T02:30+02:00")
        daily.nextDue(first!!) shouldBe at("2026-10-26T02:30+01:00")
    }

    @Test
    fun chargingAndManualHaveNoDueTimes() {
        val now = at("2026-10-14T12:00+02:00")

        SyncSchedule.WhileCharging.nextDue(now).shouldBeNull()
        SyncSchedule.Manual.previousDue(now).shouldBeNull()
    }

    @Test
    fun theTextFormIsReadBack() {
        listOf(
            "daily@03:00" to SyncSchedule.Default,
            "hours:4@01:30" to SyncSchedule.EveryHours(4, LocalTime.of(1, 30)),
            "days:3@22:00" to SyncSchedule.EveryDays(3, LocalTime.of(22, 0)),
            "weekly:SATURDAY@09:15" to SyncSchedule.Weekly(DayOfWeek.SATURDAY, LocalTime.of(9, 15)),
            "charging" to SyncSchedule.WhileCharging,
            "manual" to SyncSchedule.Manual,
        ).forEach { (text, schedule) ->
            SyncSchedule.decode(text) shouldBe schedule
            schedule.encode() shouldBe text
        }
    }

    @Test
    fun unreadableOrUnofferedTextIsRejected() {
        listOf("", "daily", "daily@3am", "hours:5@03:00", "days:7@03:00", "weekly:FUNDAY@03:00", "hourly@03:00")
            .forEach { SyncSchedule.decode(it).shouldBeNull() }
    }

    @Test
    fun theNextDueTimeAlwaysFollowsThePreviousOne() = runTest {
        checkAll(PROPERTY_ITERATIONS, timedSchedules, moments) { schedule, now ->
            val previous = schedule.previousDue(now)!!
            val next = schedule.nextDue(now)!!

            (!previous.isAfter(now) && next.isAfter(now)) shouldBe true
            // No schedule leaves more than a week (and a daylight-saving hour) between due times.
            (Duration.between(previous, next) <= Duration.ofDays(7).plusHours(1)) shouldBe true
            schedule.nextDue(previous) shouldBe next
        }
    }

    @Test
    fun everyScheduleSurvivesItsTextForm() = runTest {
        checkAll(PROPERTY_ITERATIONS, timedSchedules) { schedule ->
            SyncSchedule.decode(schedule.encode()) shouldBe schedule
        }
    }

    private val times = arbitrary { LocalTime.of(Arb.int(0..23).bind(), Arb.element(0, 15, 30, 45).bind()) }

    private val timedSchedules = arbitrary<SyncSchedule> {
        when (Arb.int(0..3).bind()) {
            0 -> SyncSchedule.Daily(times.bind())
            1 -> SyncSchedule.EveryHours(Arb.element(SyncSchedule.HOURS).bind(), times.bind())
            2 -> SyncSchedule.EveryDays(Arb.element(SyncSchedule.DAYS).bind(), times.bind())
            else -> SyncSchedule.Weekly(Arb.element(DayOfWeek.entries).bind(), times.bind())
        }
    }

    /** Moments over two years in Zurich, so both daylight-saving switches come up. */
    private val moments = arbitrary {
        Instant.ofEpochSecond(Arb.long(START_SECONDS..START_SECONDS + TWO_YEARS_SECONDS).bind()).atZone(zurich)
    }

    private companion object {
        const val PROPERTY_ITERATIONS = 2_000
        const val START_SECONDS = 1_767_225_600L // 2026-01-01
        const val TWO_YEARS_SECONDS = 2L * 366 * 24 * 60 * 60
    }
}
