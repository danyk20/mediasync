/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.danyk20.mediasync.core.designsystem.component.LocalIsComputer
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.LogSize
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Test names are sentences; a test class has many tests and magic numbers.
@Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettingsRepository()
    private val releases = FakeReleases()
    private val events = mutableListOf<String>()

    private fun viewModel() = SettingsViewModel(settings, releases, NoLoginStartup())

    @Test
    fun theLogKeepsOneMegabyteUntilTheUserTypesAnotherSize() {
        val viewModel = viewModel()
        viewModel.uiState.value.logSize shouldBe "1"
        viewModel.uiState.value.canSaveLogSize shouldBe false

        viewModel.onLogSizeChange("0,5")
        viewModel.uiState.value.canSaveLogSize shouldBe true
        viewModel.saveLogSize()

        settings.diagnosticsLogBytes.value shouldBe LogSize.MEGABYTE / 2
        viewModel.uiState.value shouldBe SettingsUiState("0.5", "0.5", updates = UpdatesUi("0.4.0"))
    }

    @Test
    fun aSizeOutsideTheLimitsIsRefusedAndNotStored() {
        val viewModel = viewModel()

        listOf("0.05", "2000", "abc", "").forEach { typed ->
            viewModel.onLogSizeChange(typed)
            viewModel.uiState.value.logSizeInvalid shouldBe true
            viewModel.uiState.value.canSaveLogSize shouldBe false
            viewModel.saveLogSize()
        }

        settings.diagnosticsLogBytes.value shouldBe LogSize.DEFAULT_BYTES
        viewModel.onLogSizeChange("1024")
        viewModel.uiState.value.logSizeInvalid shouldBe false
    }

    @Test
    fun aSizeStoredElsewhereShowsUnlessTheUserIsTyping() = runTest {
        val viewModel = viewModel()
        settings.setDiagnosticsLogBytes(20 * LogSize.MEGABYTE)
        viewModel.uiState.value.logSize shouldBe "20"

        viewModel.onLogSizeChange("3")
        settings.setDiagnosticsLogBytes(LogSize.MEGABYTE)

        viewModel.uiState.value.logSize shouldBe "3"
        viewModel.uiState.value.savedLogSize shouldBe "1"
    }

    @Test
    fun aSendingDeviceIsToldWhatItSendsAtLogin() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.value.sends shouldBe false

        settings.setRole(DeviceRole.PRODUCER)

        viewModel.uiState.value.sends shouldBe true
    }

    @Test
    fun theSendingAnimationIsOnUntilTheUserTurnsItOff() {
        val viewModel = viewModel()
        viewModel.uiState.value.sendAnimation shouldBe true

        viewModel.onSendAnimationChange(false)

        settings.sendAnimation.value shouldBe false
        viewModel.uiState.value.sendAnimation shouldBe false
    }

    @Test
    fun theWholeAnimationRowTogglesIt() {
        show(SettingsUiState("1", "1", sendAnimation = true))

        composeRule.onNodeWithText("Paper airplanes").performScrollTo().assertIsOn().performClick()
        events shouldBe listOf("animation false")

        show(SettingsUiState("1", "1", sendAnimation = false))
        composeRule.onNodeWithText("Paper airplanes").assertIsOff().performClick()
        events shouldBe listOf("animation false", "animation true")
    }

    @Test
    fun installedFromPlayThereIsNoUpdateCheck() {
        releases.fromPlay = true

        viewModel().uiState.value.updates shouldBe null
    }

    @Test
    fun aNewerReleaseIsOfferedOnlyAfterAskingGitHub() {
        val viewModel = viewModel()
        viewModel.uiState.value.updates shouldBe UpdatesUi("0.4.0")
        releases.asked shouldBe 0

        releases.latest = Release("0.5.0", RELEASE_URL)
        viewModel.checkForUpdates()
        viewModel.uiState.value.updates?.check shouldBe UpdateCheck.Newer(Release("0.5.0", RELEASE_URL))

        releases.latest = Release("0.4.0", RELEASE_URL)
        viewModel.checkForUpdates()
        viewModel.uiState.value.updates?.check shouldBe UpdateCheck.Latest

        releases.latest = null
        viewModel.checkForUpdates()
        viewModel.uiState.value.updates?.check shouldBe UpdateCheck.Failed
        releases.asked shouldBe 3
    }

    @Test
    fun versionsCompareNumberByNumber() {
        isNewer("0.5.0", "0.4.0") shouldBe true
        isNewer("0.10.0", "0.9.3") shouldBe true
        isNewer("1.0", "0.99.99") shouldBe true
        isNewer("0.4.0", "0.4.0") shouldBe false
        isNewer("0.4.0", "0.4.1") shouldBe false
        isNewer("v0.4.1", "0.4.0-debug") shouldBe true
        isNewer("nightly", "0.4.0") shouldBe false
    }

    @Test
    fun gitHubsAnswerGivesTheVersionAndThePage() {
        parseRelease("""{"tag_name": "v0.5.0", "html_url": "$RELEASE_URL", "assets": []}""") shouldBe
            Release("0.5.0", RELEASE_URL)
        parseRelease("""{"message": "Not Found"}""") shouldBe null
        parseRelease("not json") shouldBe null
    }

    @Test
    fun anAvailableReleaseOpensItsPage() {
        show(SettingsUiState("1", "1", updates = UpdatesUi("0.4.0", UpdateCheck.Newer(Release("0.5.0", RELEASE_URL)))))

        composeRule.onNodeWithText("MediaSync 0.5.0 is available.").assertExists()
        composeRule.onNodeWithText("Download").performScrollTo().performClick()

        events shouldBe listOf("open $RELEASE_URL")
    }

    @Test
    fun theTypedSizeIsSavedOnlyWhenItsValid() {
        show(SettingsUiState("1", "1"))
        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextReplacement("20")
        composeRule.onNodeWithText("From 0.1 to 1024 MB. 1 MB unless you change it.").assertIsDisplayed()
        events shouldBe listOf("size 20")

        show(SettingsUiState("20", "1"))
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        events shouldBe listOf("size 20", "save")
    }

    @Test
    fun anInvalidSizeSaysWhatsAllowed() {
        show(SettingsUiState("5000", "1", logSizeInvalid = true))

        composeRule.onNodeWithText("Type a size from 0.1 to 1024 MB, such as 0.5 or 20.").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun resettingAsksFirstAndSaysPhotosStay() {
        show(SettingsUiState("1", "1"))

        composeRule.onNodeWithText("Reset MediaSync").performScrollTo().performClick()
        composeRule.onNodeWithText("Photos and videos stay where they are", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        events shouldBe emptyList()

        composeRule.onNodeWithText("Reset MediaSync").performScrollTo().performClick()
        composeRule.onNode(hasText("Reset") and hasAnyAncestor(isDialog())).performClick()
        events shouldBe listOf("reset")
    }

    @Test
    fun aPhoneShowsNoStartAtLoginRow() {
        show(SettingsUiState("1", "1"))

        composeRule.onAllNodesWithText("Start MediaSync when you log in").assertCountEquals(0)
    }

    @Test
    fun aComputerSaysComputerAndAPhoneKeepsItsWords() {
        val login = StartAtLoginState(on = true, canChange = true)
        show(SettingsUiState("1", "1", startAtLogin = login), computer = true)
        composeRule.onNodeWithText(
            "How much of the log MediaSync keeps on this computer. " +
                "When it's full, the oldest entries make room for new ones.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "MediaSync starts hidden in the tray, so the phones and computers that send to it " +
                "can always reach this computer.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "This computer forgets its role, paired devices, settings and history, and MediaSync closes. " +
                "Photos and videos stay.",
        ).performScrollTo().assertIsDisplayed()

        show(SettingsUiState("1", "1", startAtLogin = login, sends = true), computer = true)
        composeRule.onNodeWithText(
            "On: MediaSync starts hidden in the tray at login, so your photos are sent on the schedule " +
                "without you opening it. " +
                "A sending computer doesn't turn this on by itself.",
        ).performScrollTo().assertIsDisplayed()

        show(SettingsUiState("1", "1", startAtLogin = login), computer = false)
        composeRule.onNodeWithText(
            "How much of the log MediaSync keeps on this phone. " +
                "When it's full, the oldest entries make room for new ones.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "MediaSync starts hidden in the tray, so your phones can always send to this computer.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "This phone forgets its role, paired phones, settings and history. " +
                "Photos and videos stay.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun doneCloses() {
        show(SettingsUiState("1", "1"))

        composeRule.onNodeWithText("Done").performClick()

        events shouldBe listOf("done")
    }

    @Test
    fun screenshot_light() {
        show(SettingsUiState("1", "1", updates = UpdatesUi("0.4.0")))
        composeRule.onRoot().captureScreenshot("settings_light")
    }

    @Test
    fun screenshot_dark() {
        show(SettingsUiState("1", "1"), darkTheme = true)
        composeRule.onRoot().captureScreenshot("settings_dark")
    }

    @Test
    fun screenshot_updateAvailable_light() {
        show(SettingsUiState("1", "1", updates = UpdatesUi("0.4.0", UpdateCheck.Newer(Release("0.5.0", RELEASE_URL)))))
        composeRule.onRoot().captureScreenshot("settings_update_available_light")
    }

    /** GitHub's releases, as the test sets them. */
    private class FakeReleases : Releases {
        var fromPlay = false
        var latest: Release? = null
        var asked = 0
            private set

        override fun fromPlay() = fromPlay

        override fun installed() = "0.4.0"

        override suspend fun latest(): Release? {
            asked++
            return latest
        }
    }

    private var content by mutableStateOf<SettingsUiState?>(null)
    private var onComputer by mutableStateOf(false)

    private fun show(state: SettingsUiState, darkTheme: Boolean = false, computer: Boolean = false) {
        onComputer = computer
        if (content != null) {
            content = state
            composeRule.waitForIdle()
            return
        }
        content = state
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                val shown = content ?: return@MediaSyncTheme
                CompositionLocalProvider(LocalIsComputer provides onComputer) {
                    SettingsScreen(
                        state = shown,
                        onLogSizeChange = { events += "size $it" },
                        onSaveLogSize = { events += "save" },
                        onReset = { events += "reset" },
                        onDone = { events += "done" },
                        onCheckUpdates = { events += "check" },
                        onOpenRelease = { events += "open $it" },
                        onSendAnimation = { events += "animation $it" },
                    )
                }
            }
        }
    }

    private companion object {
        const val RELEASE_URL = "https://github.com/danyk20/mediasync/releases/tag/v0.5.0"
    }
}
