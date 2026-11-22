/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.zacsweers.metro.createGraphFactory
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The window's content, headless: the receiving home opens on the desktop graph's ViewModels, on a temporary
 * folder.
 */
@OptIn(ExperimentalTestApi::class)
class WindowContentTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun showsTheReceivingScreen() {
        val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(READY_TEXT).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(READY_TEXT).assertIsDisplayed()
        }
    }

    private companion object {
        const val READY_TEXT = "Ready to receive"
        const val WAIT_MILLIS = 15_000L
    }
}
