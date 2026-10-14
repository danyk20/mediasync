/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.danyk20.mediasync.MainUiState
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The onboarding path needs Hilt and is covered by the instrumented MainActivityTest. */
@RunWith(RobolectricTestRunner::class)
class MediaSyncAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun producer_startsOnProducerHome() {
        show(MainUiState.Ready(DeviceRole.PRODUCER))

        composeRule.onNodeWithText("Not paired yet").assertIsDisplayed()
    }

    @Test
    fun consumer_startsOnConsumerHome() {
        show(MainUiState.Ready(DeviceRole.CONSUMER))

        composeRule.onNodeWithText("Ready to receive").assertIsDisplayed()
    }

    @Test
    fun loading_showsNoScreenYet() {
        show(MainUiState.Loading)

        composeRule.onAllNodesWithText("Not paired yet").fetchSemanticsNodes().size shouldBe 0
        composeRule.onAllNodesWithText("Ready to receive").fetchSemanticsNodes().size shouldBe 0
    }

    private fun show(state: MainUiState) {
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                MediaSyncApp(uiState = state)
            }
        }
    }
}
