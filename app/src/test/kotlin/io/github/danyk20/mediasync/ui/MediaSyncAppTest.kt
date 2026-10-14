/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.danyk20.mediasync.MainUiState
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Screens need their Hilt view models, so navigation between them is covered by the instrumented
 * MainActivityTest on real Android. This checks what renders without them.
 */
@RunWith(RobolectricTestRunner::class)
class MediaSyncAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading_showsNoScreenYet() {
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                MediaSyncApp(uiState = MainUiState.Loading)
            }
        }

        composeRule.onRoot().fetchSemanticsNode().children.size shouldBe 0
    }
}
