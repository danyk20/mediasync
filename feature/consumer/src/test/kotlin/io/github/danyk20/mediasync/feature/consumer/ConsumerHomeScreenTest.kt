/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
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
class ConsumerHomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val receiving = ConsumerHomeUiState(
        listening = true,
        transfer = IncomingTransfer("Anna's Pixel", 12, 40, 145_000_000, 830_000_000),
        producers = listOf("Anna's Pixel", "Tom's Galaxy"),
        receivedCount = 1234,
    )

    @Test
    fun readyWithoutProducers_invitesPairing() {
        var added = 0
        show(ConsumerHomeUiState(listening = true), onAdd = { added++ })

        composeRule.onNodeWithText("Ready to receive").assertIsDisplayed()
        composeRule.onNodeWithText("Add sending phone").performClick()
        added shouldBe 1
    }

    @Test
    fun receiving_showsWhoAndHowFar() {
        show(receiving)

        composeRule.onNodeWithText("Receiving from Anna's Pixel").assertIsDisplayed()
        composeRule.onNodeWithText("12 of 40 · 145 MB of 830 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Tom's Galaxy").assertIsDisplayed()
    }

    @Test
    fun offline_asksForWifi() {
        show(ConsumerHomeUiState(listening = false))

        composeRule.onNodeWithText("Not receiving").assertIsDisplayed()
    }

    @Test
    fun theScheduleIsShownAndCanBeChanged() {
        var changes = 0
        show(
            ConsumerHomeUiState(listening = true, schedule = SyncSchedule.EveryHours(4, LocalTime.of(1, 0))),
            onChangeSchedule = { changes++ },
        )

        composeRule.onNodeWithText("Every 4 hours from 1:00 AM").assertIsDisplayed()
        composeRule.onNodeWithText("Sync schedule").performClick()
        changes shouldBe 1
    }

    @Test
    fun screenshot_ready_light() {
        show(ConsumerHomeUiState(listening = true))
        composeRule.onRoot().captureScreenshot("consumer_home_ready_light")
    }

    @Test
    fun screenshot_receiving_light() {
        show(receiving)
        composeRule.onRoot().captureScreenshot("consumer_home_receiving_light")
    }

    @Test
    fun screenshot_receiving_dark() {
        show(receiving, darkTheme = true)
        composeRule.onRoot().captureScreenshot("consumer_home_receiving_dark")
    }

    private fun show(
        state: ConsumerHomeUiState,
        darkTheme: Boolean = false,
        onAdd: () -> Unit = {},
        onChangeSchedule: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                ConsumerHomeScreen(state = state, onAddProducer = onAdd, onChangeSchedule = onChangeSchedule)
            }
        }
    }
}
