/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.menu_settings
import io.github.danyk20.mediasync.core.designsystem.generated.resources.setup_battery_body
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The strings are Compose resources, in the phone's language, with apostrophes as typed (no `\'`). */
@RunWith(RobolectricTestRunner::class)
class ResourcesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun apostrophesAreNotEscaped() {
        composeRule.setContent { Text(stringResource(Res.string.setup_battery_body)) }
        composeRule.onNodeWithText("MediaSync's settings", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun theGermanTranslationIsUsed() {
        composeRule.setContent { Text(stringResource(Res.string.menu_settings)) }
        composeRule.onNodeWithText("Einstellungen").assertExists()
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun aRegionalTranslationIsUsed() {
        composeRule.setContent { Text(stringResource(Res.string.menu_settings)) }
        composeRule.onNodeWithText("Configurações").assertExists()
    }
}
