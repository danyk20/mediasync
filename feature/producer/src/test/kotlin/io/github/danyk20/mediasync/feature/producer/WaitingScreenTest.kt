/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WaitingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Days long past, so their headings stay the same whenever the tests run.
    private val dog = media("dog", "2025-10-29T12:00:00Z", video = true)
    private val beach = media("beach", "2025-10-28T12:00:00Z")
    private val sunset = media("sunset", "2025-10-28T11:00:00Z")
    private val cake = media("cake", "2024-12-24T12:00:00Z")
    private val ready = WaitingUiState.Ready(toSync = listOf(dog, beach, sunset), leftOut = listOf(cake))
    private val events = mutableListOf<String>()

    @Test
    fun tappingOneOpensItAndTouchingAndHoldingOneChoosesIt() {
        show(ready)

        composeRule.onNode(hasContentDescription("Video,", substring = true)).performClick()
        composeRule.onNode(hasContentDescription("Video,", substring = true)).performTouchInput { longClick() }

        events shouldBe listOf("open dog", "toggle dog")
    }

    @Test
    fun whileChoosingTheChosenOnesAreLeftOutOrTheChoiceCleared() {
        show(ready.copy(selected = setOf("dog", "beach")))

        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Video,", substring = true)).performClick()
        composeRule.onNodeWithText("Don't sync").performClick()
        composeRule.onNodeWithContentDescription("Clear selection").performClick()

        events shouldBe listOf("toggle dog", "move TO_SYNC", "clear")
    }

    @Test
    fun whileChoosingADayIsChosenAtOnce() {
        show(ready.copy(selected = setOf("dog")))

        composeRule.onNodeWithText("Tue, Oct 28, 2025").performClick()

        events shouldBe listOf("select [beach, sunset] true")
    }

    @Test
    fun whatsLeftOutIsBroughtBack() {
        show(ready.copy(selected = setOf("cake")), startWith = WaitingList.LEFT_OUT)

        composeRule.onNodeWithText("Left out · 1").assertIsDisplayed()
        composeRule.onNodeWithText("Sync").performClick()

        events shouldBe listOf("move LEFT_OUT")
    }

    @Test
    fun switchingListsClearsTheChoice() {
        show(ready)

        composeRule.onNodeWithText("Left out · 1").performClick()

        composeRule.onNodeWithText(
            "You chose not to sync these: they stay on this phone only. Touch and hold the ones to sync after all.",
        ).assertIsDisplayed()
        events shouldBe listOf("clear")
    }

    @Test
    fun screenshot_toSync_light() = screenshot(ready, "producer_waiting_light")

    @Test
    fun screenshot_choosing_light() = screenshot(ready.copy(selected = setOf("dog")), "producer_waiting_choosing_light")

    @Test
    fun screenshot_choosing_dark() =
        screenshot(ready.copy(selected = setOf("beach", "sunset")), "producer_waiting_choosing_dark", darkTheme = true)

    @Test
    fun screenshot_nothingLeftOut_light() = screenshot(
        ready.copy(leftOut = emptyList()),
        "producer_waiting_left_out_empty_light",
        startWith = WaitingList.LEFT_OUT,
    )

    @Test
    fun screenshot_loading_light() = screenshot(WaitingUiState.Loading, "producer_waiting_loading_light")

    private fun screenshot(
        state: WaitingUiState,
        name: String,
        darkTheme: Boolean = false,
        startWith: WaitingList = WaitingList.TO_SYNC,
    ) {
        show(state, darkTheme, startWith)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: WaitingUiState, darkTheme: Boolean = false, startWith: WaitingList = WaitingList.TO_SYNC) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                WaitingScreen(
                    state = state,
                    onDone = { events += "done" },
                    onToggle = { events += "toggle $it" },
                    onSelect = { keys, chosen -> events += "select $keys $chosen" },
                    onClearSelection = { events += "clear" },
                    onMove = { events += "move $it" },
                    onOpen = { events += "open ${it.key}" },
                    startWith = startWith,
                )
            }
        }
    }

    private fun media(key: String, takenAt: String, video: Boolean = false) =
        WaitingMedia(key, "content://media/external/images/media/$key", video, Instant.parse(takenAt).toEpochMilli())
}
