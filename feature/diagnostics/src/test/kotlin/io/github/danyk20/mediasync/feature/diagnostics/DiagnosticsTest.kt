/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.DiagnosticsReport
import io.github.danyk20.mediasync.sync.android.Fact
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.TestFiles
import io.github.danyk20.mediasync.sync.engine.LinkSpeed
import io.github.danyk20.mediasync.sync.engine.TestFileHeld
import io.github.danyk20.mediasync.sync.engine.TestFileOutcome
import io.github.danyk20.mediasync.sync.engine.TestFilePath
import io.github.danyk20.mediasync.sync.engine.TestFileStatus
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    private val settings = FakeSettingsRepository(initialRole = DeviceRole.PRODUCER)
    private val consumer = Peer(DeviceId.of(ByteArray(32)), "Pixel 2 XL", DeviceRole.CONSUMER, 0, null, null)
    private val peers = mockk<PeerStore> { every { observeConsumer() } returns flowOf(consumer) }
    private var speeds = listOf<LinkSpeed?>(LinkSpeed(240 * MB, 5_000_000), LinkSpeed(300 * MB, 5_000_000))
    private val sync =
        mockk<ProducerSync> { coEvery { linkTest(any(), any()) } answers { speeds[firstArg<Int>() / 4] } }

    private val testFiles = TestFiles(ApplicationProvider.getApplicationContext())

    private fun viewModel() = DiagnosticsViewModel(report, sync, settings, peers, testFiles)

    @Test
    fun itShowsTheReportAndRecentEventsAndClears() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.facts.isNotEmpty() && it.linkTest != null && it.testFile != null } shouldBe
            DiagnosticsUiState(facts, recent, LinkTestUi("Pixel 2 XL"), TestFileUi("Pixel 2 XL"))

        entries = emptyList()
        viewModel.clear()

        viewModel.uiState.first { it.recent.isEmpty() }
        coVerify { report.clear() }
        viewModel.export() shouldBe "MediaSync diagnostics"
    }

    @Test
    fun theLinkTestMeasuresOneThenFourConnections() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.linkTest != null }

        viewModel.runLinkTest()

        viewModel.uiState.first { it.linkTest?.fourConnections != null }.linkTest shouldBe
            LinkTestUi("Pixel 2 XL", oneConnection = 48.0, fourConnections = 60.0)
    }

    @Test
    fun anUnreachablePhoneIsSaidSo() = runTest {
        speeds = listOf(null, null)
        val viewModel = viewModel()
        viewModel.uiState.first { it.linkTest != null }

        viewModel.runLinkTest()

        viewModel.uiState.first { it.linkTest?.failed == true }.linkTest?.oneConnection shouldBe null
        coVerify(exactly = 1) { sync.linkTest(any(), any()) } // Four connections aren't tried.
    }

    @Test
    fun onlySendingPhonesHaveALinkTest() = runTest {
        settings.setRole(DeviceRole.CONSUMER)

        val state = viewModel().uiState.first { it.facts.isNotEmpty() }
        state.linkTest shouldBe null
        state.testFile shouldBe null // Nothing checked here yet.
    }

    @Test
    fun aSendingPhoneAddsATestFileOfTheSizeTypedAndCanRemoveIt() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.testFile != null }.testFile?.size shouldBe "25"

        viewModel.onTestFileSizeChange("20000")
        viewModel.uiState.value.testFile?.canAdd shouldBe false
        viewModel.addTestFile()
        testFiles.waiting() shouldBe null
        viewModel.onTestFileSizeChange("0,5")
        viewModel.addTestFile()

        testFiles.waiting()?.size shouldBe MB / 2
        viewModel.uiState.first { it.testFile?.waitingBytes != null }.testFile?.waitingBytes shouldBe MB / 2
        viewModel.removeTestFile()
        viewModel.uiState.first { it.testFile?.waitingBytes == null }
        testFiles.waiting() shouldBe null
    }

    @Test
    fun aReceivingPhoneShowsTheLastTestFileItChecked() = runTest {
        settings.setRole(DeviceRole.CONSUMER)
        testFiles.checked(DeviceId.of(ByteArray(32)), outcome)

        val test = viewModel().uiState.first { it.testFile != null }.testFile
        test shouldBe TestFileUi(consumerName = null, last = outcome)
    }

    @Test
    fun aTestFileIsAddedFromItsSection() {
        show(DiagnosticsUiState(facts, recent, testFile = TestFileUi("Pixel 2 XL")))

        composeRule.onNodeWithText("Add test file").performScrollTo().performClick()
        events shouldBe listOf("add test file")
        composeRule.onNodeWithText("From 0.1 MB to 10 GB", substring = true).assertExists()
    }

    @Test
    fun aWrongSizeSaysWhatFitsAndCantBeAdded() {
        show(DiagnosticsUiState(facts, recent, testFile = TestFileUi("Pixel 2 XL", size = "0", sizeInvalid = true)))

        composeRule.onNodeWithText("Type a size from 0.1 to 10240 MB, such as 25 or 500.").assertExists()
        composeRule.onNodeWithText("Add test file").assertIsNotEnabled()
    }

    @Test
    fun screenshot_testFile_light() {
        val waiting = TestFileUi(
            "Pixel 2 XL",
            waitingBytes = 25 * MB,
            problem = TestFileHeld.RECEIVER_OUTDATED,
            last = outcome,
        )
        show(DiagnosticsUiState(facts, recent, testFile = waiting))
        composeRule.onNodeWithText("Arrived exactly as sent.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("25 MB in 2.0 s: 12.5 MB/s over Turbo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Remove test file").performScrollTo().performClick()
        events shouldBe listOf("remove test file")
        composeRule.onRoot().captureScreenshot("diagnostics_test_file_light")
    }

    @Test
    fun screenshot_testFileReceived_dark() {
        val mismatched = outcome.copy(status = TestFileStatus.MISMATCHED, path = TestFilePath.RELAY)
        show(DiagnosticsUiState(facts, recent, testFile = TestFileUi(last = mismatched)), darkTheme = true)
        composeRule.onNodeWithText("Didn't arrive exactly as sent.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add test file").assertDoesNotExist()
        composeRule.onRoot().captureScreenshot("diagnostics_test_file_received_dark")
    }

    @Test
    fun theLogCanBeSharedOrSaved() {
        show(DiagnosticsUiState(facts, recent))

        composeRule.onNodeWithText("Share log").performClick()
        composeRule.onNodeWithText("Save to a file").performClick()
        composeRule.onNodeWithText("Clear log").performClick()
        composeRule.onNodeWithText("remove_location").assertIsDisplayed()
        composeRule.onNodeWithText("Check for updates").assertDoesNotExist() // It's in Settings.
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
    fun screenshot_linkTest_light() {
        show(DiagnosticsUiState(facts, recent, LinkTestUi("Pixel 2 XL", oneConnection = 48.2, fourConnections = 61.0)))
        composeRule.onNodeWithText("Four connections: 61.0 MB/s").assertIsDisplayed()
        composeRule.onNodeWithText("Test the connection").performClick()
        events shouldBe listOf("link test")
        composeRule.onRoot().captureScreenshot("diagnostics_link_test_light")
    }

    @Test
    fun screenshot_dark() = screenshot(darkTheme = true, "diagnostics_dark")

    private val outcome = TestFileOutcome(TestFileStatus.MATCHED, 25 * MB, 25 * MB, 2_000_000, TestFilePath.TURBO)

    private companion object {
        const val MB = 1024L * 1024
    }

    private fun screenshot(darkTheme: Boolean, name: String) {
        show(DiagnosticsUiState(facts, recent), darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: DiagnosticsUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                DiagnosticsScreen(
                    state = state,
                    onTestLink = { events += "link test" },
                    onShare = { events += "share" },
                    onSave = { events += "save" },
                    onClear = { events += "clear" },
                    onDone = { events += "done" },
                    testFileActions = TestFileActions(
                        onSizeChange = { events += "size $it" },
                        onAdd = { events += "add test file" },
                        onRemove = { events += "remove test file" },
                    ),
                )
            }
        }
    }
}
