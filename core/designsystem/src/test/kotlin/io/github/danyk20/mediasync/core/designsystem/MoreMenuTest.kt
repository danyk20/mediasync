/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.component.MoreMenu
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The home screens' ⋮ menu: *Diagnostics*, and *Reset MediaSync*, which asks first. */
@RunWith(RobolectricTestRunner::class)
class MoreMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    @Test
    fun resettingAsksFirstAndSaysPhotosStay() {
        show()

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Reset MediaSync").performClick()
        composeRule.onNodeWithText("Photos and videos stay where they are", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        events shouldBe emptyList()

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Reset MediaSync").performClick()
        composeRule.onNode(hasText("Reset") and hasAnyAncestor(isDialog())).performClick()
        events shouldBe listOf("reset")
    }

    @Test
    fun diagnosticsOpensFromTheMenu() {
        show()

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Diagnostics").performClick()

        events shouldBe listOf("diagnostics")
    }

    private fun show() {
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                MoreMenu(onOpenDiagnostics = { events += "diagnostics" }, onReset = { events += "reset" })
            }
        }
    }
}
