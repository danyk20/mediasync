/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SendingOrderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettingsRepository()
    private val chosen = mutableListOf<SendingOrder>()

    @Test
    fun thePhoneChoosesUntilTheUserDoesAndTheChoiceIsStored() = runTest {
        val viewModel = SendingOrderViewModel(settings)
        viewModel.uiState.value shouldBe SendingOrder.AUTOMATIC

        viewModel.choose(SendingOrder.OLDEST_FIRST)

        settings.sendingOrder.first() shouldBe SendingOrder.OLDEST_FIRST
        viewModel.uiState.first { it == SendingOrder.OLDEST_FIRST }
    }

    @Test
    fun everyOrderIsExplained() {
        show(SendingOrder.AUTOMATIC)

        composeRule.onNodeWithText("Automatic").assertIsSelected()
        composeRule.onNodeWithText("Over the internet, photos before videos", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("fewer items fit", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("share to MediaSync always go first", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Photos first").performClick()
        chosen shouldBe listOf(SendingOrder.PHOTOS_FIRST)
    }

    @Test
    fun screenshot_automatic_light() {
        show(SendingOrder.AUTOMATIC)
        composeRule.onRoot().captureScreenshot("producer_sending_order_light")
    }

    @Test
    fun screenshot_videosFirst_dark() {
        show(SendingOrder.VIDEOS_FIRST, darkTheme = true)
        composeRule.onRoot().captureScreenshot("producer_sending_order_dark")
    }

    private fun show(order: SendingOrder, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                SendingOrderScreen(order = order, onChoose = { chosen += it }, onDone = {})
            }
        }
    }
}
