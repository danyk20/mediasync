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
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import io.github.danyk20.mediasync.core.designsystem.component.LocalIsComputer
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.feature.diagnostics.DiagnosticsScreen
import io.github.danyk20.mediasync.feature.diagnostics.DiagnosticsUiState
import io.github.danyk20.mediasync.feature.diagnostics.LocalResetMediaSync
import io.github.danyk20.mediasync.feature.diagnostics.SettingsRoute
import io.github.danyk20.mediasync.feature.diagnostics.SettingsScreen
import io.github.danyk20.mediasync.feature.diagnostics.SettingsUiState
import io.github.danyk20.mediasync.feature.diagnostics.StartAtLoginState
import io.github.danyk20.mediasync.feature.diagnostics.TestFileUi
import io.github.danyk20.mediasync.feature.producer.SourceFoldersRoute
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
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

    private val graphs = TestGraphs()

    @AfterEach
    fun shutDownGraphs() = graphs.shutDown()

    @Test
    fun showsTheReceivingScreen() {
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setRole(DeviceRole.CONSUMER) }

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
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setRole(DeviceRole.CONSUMER) }
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
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setRole(DeviceRole.CONSUMER) }

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
        val graph = graphs.create(directory)

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
        val graph = graphs.create(directory)

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

    @Test
    fun firstStartAsksWhetherTheComputerSendsOrReceives() {
        val graph = graphs.create(directory)

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("This computer sends").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("This computer sends").assertIsDisplayed()
            onNodeWithText("This computer receives").assertIsDisplayed()
            onAllNodesWithText("This phone sends").fetchSemanticsNodes().isEmpty() shouldBe true
            onAllNodesWithText(READY_TEXT).fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun choosingSendsKeepsTheRoleAndShowsTheSendingHome() {
        val graph = graphs.create(directory)

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("This computer sends").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("This computer sends").performClick()
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(SENDING_HOME_TEXT).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(SENDING_HOME_TEXT).assertIsDisplayed()
            onAllNodesWithText(READY_TEXT).fetchSemanticsNodes().isEmpty() shouldBe true
        }
        runBlocking { graph.settings.role.first() } shouldBe DeviceRole.PRODUCER
    }

    @Test
    fun choosingReceivesShowsTheReceivingHome() {
        val graph = graphs.create(directory)

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("This computer receives").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("This computer receives").performClick()
            waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithText(READY_TEXT).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(READY_TEXT).assertIsDisplayed()
        }
        runBlocking { graph.settings.role.first() } shouldBe DeviceRole.CONSUMER
    }

    @Test
    fun aRoleAlreadyChosenOpensItsHomeWithoutAsking() {
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setRole(DeviceRole.PRODUCER) }

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(SENDING_HOME_TEXT).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText("This computer sends").fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun theSendersFolderListOffersAddFolderAndNoPresets() {
        val graph = graphs.create(directory)
        val photos = java.nio.file.Files.createDirectories(directory.resolve("Holiday photos"))
        runBlocking { graph.folders.add(photos) }

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalMetroViewModelFactory provides graph.viewModelFactory) {
                    MediaSyncTheme { SourceFoldersRoute(onDone = {}) }
                }
            }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("Holiday photos").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Add folder\u2026").assertIsDisplayed()
            onNodeWithText("Camera only").assertDoesNotExist()
        }
    }

    @Test
    fun resetMediaSyncInSettingsAsksFirstThenRunsTheDesktopReset() {
        val graph = graphs.create(directory)
        var resets = 0

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalMetroViewModelFactory provides graph.viewModelFactory,
                    LocalIsComputer provides true,
                    LocalResetMediaSync provides { resets++ },
                ) { MediaSyncTheme { SettingsRoute(onDone = {}) } }
            }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("Reset MediaSync").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Reset MediaSync").performScrollTo().performClick()
            onNodeWithText("MediaSync then closes", substring = true).assertIsDisplayed()
            resets shouldBe 0
            onNodeWithText("Reset").performClick()
        }
        resets shouldBe 1
    }

    @Test
    fun theReceivingHomeSaysComputerAndHasNoInternetSyncOrSimpleFileNames() {
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setRole(DeviceRole.CONSUMER) }

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("This computer receives").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("This computer receives").assertIsDisplayed()
            onNodeWithText(BACKUP_ROW).assertIsDisplayed()
            onAllNodesWithText("This phone receives").fetchSemanticsNodes().isEmpty() shouldBe true
            onAllNodesWithText("Simple file names").fetchSemanticsNodes().isEmpty() shouldBe true
            onAllNodesWithText("Internet sync").fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun theReceivingHomeOnAComputerHasNoTurboRow() {
        val graph = graphs.create(directory)
        runBlocking { graph.settings.setRole(DeviceRole.CONSUMER) }

        runComposeUiTest {
            setContent { DesktopRoot(graph) }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText(READY_TEXT).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText("Turbo receive", substring = true).fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun theDiagnosticsOnAComputerHasNoTestFile() {
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalIsComputer provides true) {
                    MediaSyncTheme {
                        DiagnosticsScreen(state = testFileState(), onTestLink = {
                        }, onShare = {}, onSave = {}, onClear = {}, onDone = {})
                    }
                }
            }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("If MediaSync doesn't work", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText("Test file").fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun theDiagnosticsOnAPhoneHasTheTestFile() {
        runComposeUiTest {
            setContent {
                MediaSyncTheme {
                    DiagnosticsScreen(state = testFileState(), onTestLink = {
                    }, onShare = {}, onSave = {}, onClear = {}, onDone = {})
                }
            }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithText("Test file").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun testFileState() = DiagnosticsUiState(testFile = TestFileUi(consumerName = "Anna's Mac"))

    private companion object {
        const val BACKUP_ROW = "Backup folder"
        const val READY_TEXT = "Ready to receive"
        const val SENDING_HOME_TEXT = "Not paired yet"
        const val WAIT_MILLIS = 15_000L
    }
}
