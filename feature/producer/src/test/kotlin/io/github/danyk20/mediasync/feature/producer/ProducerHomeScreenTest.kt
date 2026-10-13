/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProducerHomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsNotPairedStatus() {
        showScreen()

        composeRule.onNodeWithText("Not paired yet").assertIsDisplayed()
    }

    @Test
    fun screenshot_light() {
        showScreen(darkTheme = false)
        composeRule.onRoot().captureScreenshot("producer_home_light")
    }

    @Test
    fun screenshot_dark() {
        showScreen(darkTheme = true)
        composeRule.onRoot().captureScreenshot("producer_home_dark")
    }

    private fun showScreen(darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                ProducerHomeScreen()
            }
        }
    }
}
