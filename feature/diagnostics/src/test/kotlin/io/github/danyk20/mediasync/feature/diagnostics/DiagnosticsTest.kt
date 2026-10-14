/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.DiagnosticsReport
import io.github.danyk20.mediasync.sync.android.Fact
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
class DiagnosticsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val facts = listOf(
        Fact("App", "MediaSync 0.1.0"),
        Fact("Android", "16 (API 36)"),
        Fact("Role", "producer"),
        Fact("Photo details", "remove_location"),
        Fact("Battery use", "not restricted"),
        Fact("Work: scheduled-sync", "enqueued, try 1, next at 2026-10-15 03:00 +02:00"),
    )
    private val recent = listOf(
        "2026-10-14 09:30:00.250 +0200 schedule Next producer run (daily@03:00) in 17 h 30 min, while charging",
        "2026-10-14 09:31:02.118 +0200 send     Sync done in 4.2 s: sent 3 (12.0 MB)",
    )
    private var entries = recent
    private val report = mockk<DiagnosticsReport>(relaxed = true) {
        coEvery { facts() } returns facts
        coEvery { recent(any()) } answers { entries }
        coEvery { export() } returns "MediaSync diagnostics"
    }
    private val events = mutableListOf<String>()

    @Test
    fun itShowsTheReportAndRecentEventsAndClears() = runTest {
        val viewModel = DiagnosticsViewModel(report)
        viewModel.uiState.first { it.facts.isNotEmpty() } shouldBe DiagnosticsUiState(facts, recent)

        entries = emptyList()
        viewModel.clear()

        viewModel.uiState.first { it.recent.isEmpty() }
        coVerify { report.clear() }
        viewModel.export() shouldBe "MediaSync diagnostics"
    }

    @Test
    fun theLogCanBeSharedOrSaved() {
        show(DiagnosticsUiState(facts, recent))

        composeRule.onNodeWithText("Share log").performClick()
        composeRule.onNodeWithText("Save to a file").performClick()
        composeRule.onNodeWithText("Clear log").performClick()
        composeRule.onNodeWithText("remove_location").assertIsDisplayed()
        events shouldBe listOf("share", "save", "clear")
    }

    @Test
    fun anEmptyLogSaysSo() {
        show(DiagnosticsUiState(facts, emptyList()))

        composeRule.onNodeWithText("Nothing logged yet.").assertIsDisplayed()
    }

    @Test
    fun screenshot_light() = screenshot(darkTheme = false, "diagnostics_light")

    @Test
    fun screenshot_dark() = screenshot(darkTheme = true, "diagnostics_dark")

    private fun screenshot(darkTheme: Boolean, name: String) {
        show(DiagnosticsUiState(facts, recent), darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: DiagnosticsUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                DiagnosticsScreen(
                    state = state,
                    onShare = { events += "share" },
                    onSave = { events += "save" },
                    onClear = { events += "clear" },
                    onDone = { events += "done" },
                )
            }
        }
    }
}
