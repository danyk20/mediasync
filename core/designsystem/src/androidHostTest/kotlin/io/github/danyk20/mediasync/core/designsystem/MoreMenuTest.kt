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
import io.github.danyk20.mediasync.core.designsystem.component.ResetDialog
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The home screens' ⋮ menu: *Settings* and *Diagnostics*; and the question before *Reset MediaSync*. */
@RunWith(RobolectricTestRunner::class)
class MoreMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    @Test
    fun settingsOpensFromTheMenu() {
        showMenu()

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Diagnostics").assertIsDisplayed()
        composeRule.onNodeWithText("Reset MediaSync").assertDoesNotExist() // It's in Settings now.
        composeRule.onNodeWithText("Settings").performClick()

        events shouldBe listOf("settings")
    }

    @Test
    fun diagnosticsOpensFromTheMenu() {
        showMenu()

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Diagnostics").performClick()

        events shouldBe listOf("diagnostics")
    }

    @Test
    fun resettingSaysPhotosStayAndCanBeCancelled() {
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                ResetDialog(onReset = { events += "reset" }, onCancel = { events += "cancel" })
            }
        }

        composeRule.onNodeWithText("Photos and videos stay where they are", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNode(hasText("Reset") and hasAnyAncestor(isDialog())).performClick()

        events shouldBe listOf("cancel", "reset")
    }

    private fun showMenu() {
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                MoreMenu(onOpenSettings = { events += "settings" }, onOpenDiagnostics = { events += "diagnostics" })
            }
        }
    }
}
