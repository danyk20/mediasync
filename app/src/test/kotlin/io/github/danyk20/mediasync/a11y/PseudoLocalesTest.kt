/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.a11y

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_mediasync
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Debug builds enable Android's pseudo-locales (en-XA, ar-XB), but the strings are Compose resources now, which Android
 * does not pseudo-localize: en-XA shows plain English. What still works, and is pinned here: ar-XB mirrors the layout,
 * and a real language picks its translation (docs/TRANSLATING.md, "Checking a layout without a translation").
 */
@RunWith(RobolectricTestRunner::class)
class PseudoLocalesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "ar-rXB")
    fun pseudoArabicMirrorsTheLayout() {
        var direction by mutableStateOf<LayoutDirection?>(null)
        composeRule.setContent { direction = LocalLayoutDirection.current }
        direction shouldBe LayoutDirection.Rtl
    }

    @Test
    @Config(qualifiers = "en-rXA")
    fun pseudoEnglishStaysPlainEnglishInComposeResources() {
        resetText() shouldBe "Reset MediaSync"
    }

    @Test
    @Config(qualifiers = "cs")
    fun aRealLanguagePicksItsTranslation() {
        resetText() shouldBe "Obnovit MediaSync"
    }

    private fun resetText(): String {
        var text by mutableStateOf("")
        composeRule.setContent { text = stringResource(Res.string.reset_mediasync) }
        composeRule.waitForIdle()
        return text
    }
}
