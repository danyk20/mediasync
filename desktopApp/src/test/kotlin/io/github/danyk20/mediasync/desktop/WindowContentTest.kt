/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.feature.diagnostics.SettingsRoute
import io.github.danyk20.mediasync.feature.diagnostics.SettingsScreen
import io.github.danyk20.mediasync.feature.diagnostics.SettingsUiState
import io.github.danyk20.mediasync.feature.diagnostics.StartAtLoginState
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
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

    @Test
    fun theBackupScreenShowsTheChosenFolder() {
        val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))
        val folder = directory.resolve("My Backup").toString()
        runBlocking { graph.settings.setBackupFolder(folder) }

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(BACKUP_ROW).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(BACKUP_ROW).performClick()
            waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithText(folder).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(folder).assertIsDisplayed()
            onNodeWithText("Change folder").assertIsDisplayed()
        }
    }

    @Test
    fun theBackupScreenOffersToChooseAFolderWhenThereIsNone() {
        val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(BACKUP_ROW).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(BACKUP_ROW).performClick()
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("Choose folder").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Choose folder").assertIsDisplayed()
        }
    }

    @Test
    fun showsTheWaitingStateWhileTheKeychainIsLocked() {
        val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))

        runComposeUiTest {
            setContent { DesktopRoot(graph, waitingForKeychain = true) }
            onNodeWithText(WAITING_TEXT).assertIsDisplayed()
            onAllNodesWithText(READY_TEXT).fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun settingsOffersStartAtLoginAndTurnsItOnWithOneClick() {
        val events = mutableListOf<Boolean>()
        runComposeUiTest {
            setContent {
                MediaSyncTheme {
                    SettingsScreen(
                        state = SettingsUiState(
                            "1",
                            "1",
                            startAtLogin = StartAtLoginState(on = false, canChange = true),
                        ),
                        onLogSizeChange = {},
                        onSaveLogSize = {},
                        onReset = {},
                        onDone = {},
                        onStartAtLogin = { events += it },
                    )
                }
            }
            onNodeWithText("Start MediaSync when you log in").performScrollTo().assertIsOff().performClick()
        }
        events shouldBe listOf(true)
    }

    @Test
    fun settingsSaysWhyStartAtLoginIsOffForACopyThatIsNotInstalled() {
        val events = mutableListOf<Boolean>()
        runComposeUiTest {
            setContent {
                MediaSyncTheme {
                    SettingsScreen(
                        state = SettingsUiState(
                            "1",
                            "1",
                            startAtLogin = StartAtLoginState(on = false, canChange = false),
                        ),
                        onLogSizeChange = {},
                        onSaveLogSize = {},
                        onReset = {},
                        onDone = {},
                        onStartAtLogin = { events += it },
                    )
                }
            }
            onNodeWithText("Only an installed MediaSync", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText("Start MediaSync when you log in").performClick()
        }
        events shouldBe emptyList()
    }

    @Test
    fun settingsInTheWindowShowsTheRowDisabledWhenNotInstalled() {
        val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalMetroViewModelFactory provides graph.viewModelFactory) {
                    MediaSyncTheme { SettingsRoute(onDone = {}) }
                }
            }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("Start MediaSync when you log in").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Only an installed MediaSync", substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    private companion object {
        const val BACKUP_ROW = "Cloud backup"
        const val READY_TEXT = "Ready to receive"
        const val WAIT_MILLIS = 15_000L
    }
}
