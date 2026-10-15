/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class InternetSyncTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettingsRepository()
    private val receiverState = MutableStateFlow(ReceiverState())
    private val receiver = mockk<ConsumerReceiver>(relaxed = true) { every { state } returns receiverState }
    private val viewModel by lazy { InternetSyncViewModel(settings, receiver) }
    private val events = mutableListOf<String>()

    @Test
    fun aWorkerAddressIsAnHttpsHost() {
        workerAddress("https://mediasync.anna.workers.dev/") shouldBe "https://mediasync.anna.workers.dev"
        workerAddress("  MediaSync.Anna.Workers.dev ") shouldBe "https://mediasync.anna.workers.dev"
        workerAddress("https://sync.example.org:8443") shouldBe "https://sync.example.org:8443"
        workerAddress("http://mediasync.anna.workers.dev") shouldBe null // Sending phones only accept https.
        workerAddress("https://mediasync.anna.workers.dev/v1/pairs") shouldBe null
        workerAddress("https://anna:secret@mediasync.anna.workers.dev") shouldBe null
        workerAddress("localhost") shouldBe null
        workerAddress("not an address") shouldBe null
        workerAddress("") shouldBe null
    }

    @Test
    fun savingSetsTheWorkerAndListensThroughIt() {
        viewModel.save("mediasync.anna.workers.dev") shouldBe true

        settings.rendezvousUrl.value shouldBe "https://mediasync.anna.workers.dev"
        verify { receiver.refreshInternet() }
    }

    @Test
    fun anAddressThatIsntOneIsNotSaved() {
        viewModel.save("http://mediasync.anna.workers.dev") shouldBe false

        settings.rendezvousUrl.value shouldBe null
        verify(exactly = 0) { receiver.refreshInternet() }
    }

    @Test
    fun turningOffForgetsTheWorker() {
        viewModel.save("https://mediasync.anna.workers.dev")

        viewModel.turnOff()

        settings.rendezvousUrl.value shouldBe null
        verify(exactly = 2) { receiver.refreshInternet() }
    }

    @Test
    fun itSaysWhetherSendingPhonesCanReachThisPhone() {
        viewModel.uiState.value shouldBe InternetSyncUiState()
        viewModel.save("https://mediasync.anna.workers.dev")
        viewModel.uiState.value shouldBe InternetSyncUiState("https://mediasync.anna.workers.dev", reachable = false)

        receiverState.value = ReceiverState(overInternet = true)

        viewModel.uiState.value shouldBe InternetSyncUiState("https://mediasync.anna.workers.dev", reachable = true)
    }

    @Test
    fun aWrongAddressIsShownAsSuch() {
        show(InternetSyncUiState(), accepts = false)

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Worker address").performTextReplacement("http://example.org")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithText("Enter an https address, like https://mediasync.example.workers.dev")
            .assertIsDisplayed()
        events shouldBe listOf("save http://example.org")
    }

    @Test
    fun aWorkerCanBeTurnedOff() {
        show(InternetSyncUiState("https://mediasync.anna.workers.dev"))

        composeRule.onNodeWithText("Turn off").performClick()
        composeRule.onNodeWithText("Done").performClick()

        events shouldBe listOf("off", "done")
    }

    @Test
    fun screenshot_off_light() = screenshot(InternetSyncUiState(), "consumer_internet_off_light")

    @Test
    fun screenshot_reachable_dark() = screenshot(
        InternetSyncUiState("https://mediasync.anna.workers.dev", reachable = true),
        "consumer_internet_reachable_dark",
        darkTheme = true,
    )

    private fun screenshot(state: InternetSyncUiState, name: String, darkTheme: Boolean = false) {
        show(state, darkTheme = darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: InternetSyncUiState, accepts: Boolean = true, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                InternetSyncScreen(
                    state = state,
                    onSave = {
                        events += "save $it"
                        accepts
                    },
                    onTurnOff = { events += "off" },
                    onDone = { events += "done" },
                )
            }
        }
    }
}
