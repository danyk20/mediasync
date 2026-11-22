/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.feature.consumer.SyncScheduleRoute
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The schedule screen on a computer, headless: it says the computer receives whenever MediaSync runs. */
@OptIn(ExperimentalTestApi::class)
class ComputerScheduleTest {

    @TempDir
    lateinit var directory: Path

    private val graphs = TestGraphs()

    @AfterEach
    fun shutDownGraphs() = graphs.shutDown()

    @Test
    fun theScheduleScreenWordsTheComputerAsReceivingWheneverTheAppRuns() {
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setSyncSchedule(SyncSchedule.WhileCharging) }

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalMetroViewModelFactory provides graph.viewModelFactory) {
                    MediaSyncTheme { SyncScheduleRoute(onDone = {}) }
                }
            }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(COMPUTER_DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(COMPUTER_INTRO).assertIsDisplayed()
            onNodeWithText(COMPUTER_DESCRIPTION).assertIsDisplayed()
            onNodeWithText(OPEN_HINT).assertIsDisplayed()
            onAllNodesWithText(PHONE_DESCRIPTION).fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val COMPUTER_INTRO =
            "Your sending phones sync with this computer at these times while they're charging. " +
                "A phone that isn't charging then syncs as soon as it's plugged in."
        const val COMPUTER_DESCRIPTION = "Phones sync as soon as they're charging and have new photos. " +
            "This computer receives whenever MediaSync is running."
        const val PHONE_DESCRIPTION =
            "Phones sync as soon as they're charging and have new photos. This phone listens " +
                "while it's charging."
        const val OPEN_HINT = "While MediaSync is open, your phones can sync with this computer at any time."
    }
}
