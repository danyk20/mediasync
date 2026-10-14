/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RoleChoiceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var chosenRole: DeviceRole? = null

    @Test
    fun tappingSends_choosesProducer() {
        showScreen()

        composeRule.onNodeWithText("This phone sends").performClick()

        chosenRole shouldBe DeviceRole.PRODUCER
    }

    @Test
    fun tappingReceives_choosesConsumer() {
        showScreen()

        composeRule.onNodeWithText("This phone receives").performClick()

        chosenRole shouldBe DeviceRole.CONSUMER
    }

    @Test
    fun screenshot_light() {
        showScreen(darkTheme = false)
        composeRule.onRoot().captureScreenshot("role_choice_light")
    }

    @Test
    fun screenshot_dark() {
        showScreen(darkTheme = true)
        composeRule.onRoot().captureScreenshot("role_choice_dark")
    }

    private fun showScreen(darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                RoleChoiceScreen(onRoleChosen = { chosenRole = it })
            }
        }
    }
}
