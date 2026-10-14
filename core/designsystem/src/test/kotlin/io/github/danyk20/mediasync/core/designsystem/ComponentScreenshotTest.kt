/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.component.ChoiceCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusCard
import io.github.danyk20.mediasync.core.designsystem.component.StatusTone
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
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
@Config(qualifiers = "w400dp-h400dp-xxhdpi")
class ComponentScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun components_light() = captureComponents(darkTheme = false, name = "components_light")

    @Test
    fun components_dark() = captureComponents(darkTheme = true, name = "components_dark")

    @Test
    fun choiceCard_click_invokesCallback() {
        var clicks = 0
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                ChoiceCard(
                    title = "Option",
                    description = "Details",
                    icon = MediaSyncIcons.Send,
                    onClick = { clicks++ },
                )
            }
        }

        composeRule.onNodeWithText("Option").assertHasClickAction().performClick()

        clicks shouldBe 1
    }

    private fun captureComponents(darkTheme: Boolean, name: String) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusCard(
                            title = "Up to date",
                            body = "Next sync tonight at 03:00",
                            icon = MediaSyncIcons.Info,
                            tone = StatusTone.Positive,
                        )
                        StatusCard(title = "Not paired yet", body = "Scan the QR code", icon = MediaSyncIcons.Info)
                        ChoiceCard(
                            title = "This phone sends",
                            description = "Sends its photos and videos.",
                            icon = MediaSyncIcons.Send,
                            onClick = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureScreenshot(name)
    }
}
