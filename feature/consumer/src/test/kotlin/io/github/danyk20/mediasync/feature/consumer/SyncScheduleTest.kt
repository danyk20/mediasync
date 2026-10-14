/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SyncScheduleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettingsRepository()
    private val viewModel by lazy { SyncScheduleViewModel(settings) }
    private val events = mutableListOf<String>()

    @Test
    fun startsDailyAtThree() {
        viewModel.schedule.value shouldBe SyncSchedule.Default
    }

    @Test
    fun switchingKeepsTheChosenTime() {
        viewModel.setTime(LocalTime.of(22, 30))

        viewModel.choose(ScheduleOption.EVERY_HOURS)
        settings.syncSchedule.value shouldBe SyncSchedule.EveryHours(6, LocalTime.of(22, 30))

        viewModel.setHours(2)
        settings.syncSchedule.value shouldBe SyncSchedule.EveryHours(2, LocalTime.of(22, 30))

        viewModel.choose(ScheduleOption.WEEKLY)
        viewModel.setDay(DayOfWeek.MONDAY)
        settings.syncSchedule.value shouldBe SyncSchedule.Weekly(DayOfWeek.MONDAY, LocalTime.of(22, 30))

        viewModel.choose(ScheduleOption.EVERY_DAYS)
        viewModel.setDays(3)
        settings.syncSchedule.value shouldBe SyncSchedule.EveryDays(3, LocalTime.of(22, 30))
    }

    @Test
    fun choosingTheCurrentOptionAgainChangesNothing() {
        viewModel.choose(ScheduleOption.EVERY_HOURS)
        viewModel.setHours(12)

        viewModel.choose(ScheduleOption.EVERY_HOURS)

        settings.syncSchedule.value shouldBe SyncSchedule.EveryHours(12, LocalTime.of(3, 0))
    }

    @Test
    fun withoutATimeNothingIsTimed() {
        viewModel.choose(ScheduleOption.WHILE_CHARGING)
        viewModel.setTime(LocalTime.NOON)
        settings.syncSchedule.value shouldBe SyncSchedule.WhileCharging

        viewModel.choose(ScheduleOption.DAILY)
        settings.syncSchedule.value shouldBe SyncSchedule.Default // Back to the default time.
    }

    @Test
    fun theScreenShowsTheChoiceAndItsDetails() {
        show(SyncSchedule.EveryHours(6, LocalTime.of(3, 0)))

        composeRule.onNodeWithText("Every few hours").assertIsSelected()
        composeRule.onNodeWithText("6 hours").assertIsSelected()
        composeRule.onNodeWithText("From 3:00 AM").assertIsDisplayed()

        composeRule.onNodeWithText("2 hours").performClick()
        composeRule.onNodeWithText("Whenever charging").performClick()
        composeRule.onNodeWithText("Done").performClick()

        events shouldBe listOf("hours 2", "choose WHILE_CHARGING", "done")
    }

    @Test
    fun theTimeIsPickedInADialog() {
        show(SyncSchedule.Default)

        composeRule.onNodeWithText("At 3:00 AM").performClick()
        composeRule.onNodeWithText("OK").performClick()

        events shouldBe listOf("time 03:00")
    }

    @Test
    fun screenshot_daily_light() = screenshot(SyncSchedule.Default, "consumer_schedule_daily_light")

    @Test
    fun screenshot_weekly_dark() = screenshot(
        SyncSchedule.Weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0)),
        "consumer_schedule_weekly_dark",
        darkTheme = true,
    )

    @Test
    fun screenshot_whileCharging_light() = screenshot(SyncSchedule.WhileCharging, "consumer_schedule_charging_light")

    private fun screenshot(schedule: SyncSchedule, name: String, darkTheme: Boolean = false) {
        show(schedule, darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(schedule: SyncSchedule, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                SyncScheduleScreen(
                    schedule = schedule,
                    actions = ScheduleActions(
                        choose = { events += "choose $it" },
                        setTime = { events += "time $it" },
                        setHours = { events += "hours $it" },
                        setDays = { events += "days $it" },
                        setDay = { events += "day $it" },
                    ),
                    onDone = { events += "done" },
                )
            }
        }
    }
}
