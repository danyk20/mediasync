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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.android.SetupFailure
import io.github.danyk20.mediasync.sync.android.SetupResult
import io.github.danyk20.mediasync.sync.android.SetupStep
import io.github.danyk20.mediasync.sync.android.WorkerSetup
import io.github.danyk20.mediasync.transport.rendezvous.CloudflareOAuth
import io.github.danyk20.mediasync.transport.rendezvous.HttpResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val usage = RelayUsage(
        usedBytes = 312_400_000_000,
        limitBytes = 950_000_000_000,
        freeBytes = 1_000_000_000_000,
        available = true,
        resetsAt = Instant.parse("2026-11-01T00:00:00Z"),
    )
    private val receiver = mockk<ConsumerReceiver>(relaxed = true) {
        every { state } returns receiverState
        every { relayUsage() } returns usage
    }
    private var deployment: SetupResult = SetupResult.Done(
        "https://mediasync-rendezvous.anna.workers.dev",
        relay = true,
    )
    private val workerSetup = mockk<WorkerSetup> {
        every { available() } returns true
        coEvery { deploy(any(), any()) } coAnswers {
            secondArg<(SetupStep) -> Unit>()(SetupStep.UPLOAD)
            deployment
        }
    }

    /** Cloudflare's token endpoint, as faked here: a status and body for the code exchange. */
    private var tokenAnswer = 200 to """{"access_token":"cf-oauth-token","token_type":"bearer","expires_in":3600}"""
    private val exchanges = mutableListOf<String>()
    private val oauth = CloudflareOAuth("client-1", REDIRECT, http = { request ->
        exchanges += request.body!!.decodeToString()
        HttpResponse(tokenAnswer.first, emptyMap(), tokenAnswer.second.encodeToByteArray())
    })
    private val cloudflare = CloudflareSignIn(oauth)
    private val saved = SavedStateHandle()
    private val viewModel by lazy { InternetSyncViewModel(settings, receiver, workerSetup, cloudflare, saved) }
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
    fun itSaysWhetherSendingPhonesCanReachThisPhone() = runBlocking<Unit> {
        viewModel.uiState.value shouldBe InternetSyncUiState(setup = CloudflareSetup.Ready, signIn = true)
        viewModel.save("https://mediasync.anna.workers.dev")
        val on = withTimeout(WAIT) { viewModel.uiState.first { it?.worker != null } }
        on?.reachable shouldBe false

        receiverState.value = ReceiverState(overInternet = true)

        // The meter is read meanwhile, on another thread: wait for the state, don't race it.
        withTimeout(WAIT) { viewModel.uiState.first { it?.reachable == true } }?.worker shouldBe
            "https://mediasync.anna.workers.dev"
    }

    @Test
    fun theRelayMeterIsReadOnceThereIsAWorker() = runBlocking<Unit> {
        viewModel.uiState.value?.relay shouldBe null
        verify(exactly = 0) { receiver.relayUsage() }

        viewModel.save("https://mediasync.anna.workers.dev")

        withTimeout(WAIT) { viewModel.uiState.first { it?.relay != null } }?.relay shouldBe usage
    }

    @Test
    fun theMeterSaysHowMuchOfTheAllowanceIsUsed() {
        show(InternetSyncUiState("https://mediasync.anna.workers.dev", relay = usage))

        composeRule.onNodeWithText("312 GB of 1,000 GB free").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aUsedUpAllowanceSaysUntilWhen() {
        show(InternetSyncUiState("https://mediasync.anna.workers.dev", relay = usage.copy(available = false)))

        composeRule.onNodeWithText("The relay allowance is used up", substring = true).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun settingUpWithCloudflareDeploysWithTheTokenAndSaysHowItWent() = runBlocking<Unit> {
        viewModel.uiState.value?.setup shouldBe CloudflareSetup.Ready

        viewModel.setUp("token-123")

        withTimeout(WAIT) { viewModel.uiState.first { it?.setup is CloudflareSetup.Finished } }?.setup shouldBe
            CloudflareSetup.Finished(deployment)
        coVerify { workerSetup.deploy("token-123", any()) }
        viewModel.dismissSetup()
        viewModel.uiState.value?.setup shouldBe CloudflareSetup.Ready
    }

    @Test
    fun signingInWithCloudflareDeploysWithItsAccessToken() = runBlocking<Unit> {
        viewModel.uiState.value?.signIn shouldBe true
        val page = viewModel.signIn()!!
        page shouldStartWith "https://dash.cloudflare.com/oauth2/auth?"
        viewModel.uiState.value?.setup shouldBe CloudflareSetup.SigningIn

        cloudflare.receive("$REDIRECT?code=code-1&state=${stateOf(page)}")
        viewModel.returned()

        withTimeout(WAIT) { viewModel.uiState.first { it?.setup is CloudflareSetup.Finished } }?.setup shouldBe
            CloudflareSetup.Finished(deployment, signedIn = true)
        exchanges.single() shouldContain "code=code-1"
        coVerify { workerSetup.deploy("cf-oauth-token", any()) }
    }

    @Test
    fun comingBackWithoutAnAnswerCancelsTheSignIn() {
        viewModel.signIn()

        viewModel.returned()

        viewModel.uiState.value?.setup shouldBe
            CloudflareSetup.Finished(SetupResult.Failed(SetupFailure.SIGN_IN_CANCELLED))
        exchanges shouldBe emptyList()
        coVerify(exactly = 0) { workerSetup.deploy(any(), any()) }
    }

    @Test
    fun deniedAccessCancelsTheSignIn() = runBlocking<Unit> {
        val page = viewModel.signIn()!!

        cloudflare.receive("$REDIRECT?error=access_denied&state=${stateOf(page)}")
        viewModel.returned()

        finished() shouldBe CloudflareSetup.Finished(SetupResult.Failed(SetupFailure.SIGN_IN_CANCELLED))
        exchanges shouldBe emptyList()
    }

    @Test
    fun anAnswerForAnotherSignInIsIgnored() = runBlocking<Unit> {
        viewModel.signIn()

        cloudflare.receive("$REDIRECT?code=code-1&state=someone-elses")
        viewModel.returned()

        finished() shouldBe CloudflareSetup.Finished(SetupResult.Failed(SetupFailure.SIGN_IN_MISMATCH))
        exchanges shouldBe emptyList()
        coVerify(exactly = 0) { workerSetup.deploy(any(), any()) }
    }

    @Test
    fun aRefusedCodeSaysWhy() = runBlocking<Unit> {
        tokenAnswer = 400 to """{"error":"invalid_grant","error_description":"The code has expired"}"""
        val page = viewModel.signIn()!!

        cloudflare.receive("$REDIRECT?code=code-1&state=${stateOf(page)}")
        viewModel.returned()

        finished() shouldBe CloudflareSetup.Finished(
            SetupResult.Failed(SetupFailure.SIGN_IN_REFUSED, "The code has expired"),
        )
        coVerify(exactly = 0) { workerSetup.deploy(any(), any()) }
    }

    @Test
    fun aSignInOutlivesTheAppBeingEndedWhileTheBrowserIsOpen() = runBlocking<Unit> {
        val page = viewModel.signIn()!!
        // Android ends the app; the screen comes back with what it saved.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<String>(it) })
        val restarted = CloudflareSignIn(oauth)
        val again = InternetSyncViewModel(settings, receiver, workerSetup, restarted, restored)
        again.uiState.value?.setup shouldBe CloudflareSetup.SigningIn

        restarted.receive("$REDIRECT?code=code-1&state=${stateOf(page)}")
        again.returned()

        withTimeout(WAIT) { again.uiState.first { it?.setup is CloudflareSetup.Finished } }
        coVerify { workerSetup.deploy("cf-oauth-token", any()) }
    }

    @Test
    fun aStrayAnswerWithoutASignInIsDropped() {
        cloudflare.receive("$REDIRECT?code=code-1&state=state-1")

        viewModel.returned()

        cloudflare.take() shouldBe null
        viewModel.uiState.value?.setup shouldBe CloudflareSetup.Ready
    }

    @Test
    fun onlyTheRedirectAddressIsKept() {
        cloudflare.receive("https://evil.example/oauth/cloudflare?code=code-1&state=state-1")

        cloudflare.take() shouldBe null
    }

    @Test
    fun aBuildWithoutTheOAuthClientTakesATokenOnly() {
        val tokenOnly = InternetSyncViewModel(settings, receiver, workerSetup, CloudflareSignIn(null), saved)

        tokenOnly.uiState.value?.signIn shouldBe false
        tokenOnly.signIn() shouldBe null
        tokenOnly.uiState.value?.setup shouldBe CloudflareSetup.Ready
    }

    @Test
    fun aBuildWithoutTheWorkerOnlyTakesAnAddress() {
        every { workerSetup.available() } returns false

        viewModel.uiState.value?.setup shouldBe CloudflareSetup.Unavailable
        viewModel.uiState.value?.signIn shouldBe false
        viewModel.setUp("token-123")
        viewModel.signIn() shouldBe null
        coVerify(exactly = 0) { workerSetup.deploy(any(), any()) }
    }

    @Test
    fun theSetupCardTakesTheToken() {
        show(InternetSyncUiState(setup = CloudflareSetup.Ready))

        composeRule.onNodeWithText("Create a token at Cloudflare").performScrollTo().performClick()
        composeRule.onNodeWithText("Set up").assertIsNotEnabled()
        composeRule.onNodeWithText("Cloudflare token").performTextReplacement("token-123")
        composeRule.onNodeWithText("Set up").performScrollTo().performClick()

        events shouldBe listOf("token page", "set up token-123")
    }

    @Test
    fun signingInIsTheFirstChoiceAndTheTokenStepsAreFolded() {
        show(InternetSyncUiState(setup = CloudflareSetup.Ready, signIn = true))
        composeRule.onNodeWithText("Cloudflare token").assertDoesNotExist()

        composeRule.onNodeWithText("Sign in with Cloudflare").performScrollTo().performClick()
        composeRule.onNodeWithText("Or use an API token").performScrollTo().performClick()
        composeRule.onNodeWithText("Cloudflare token").performTextReplacement("token-123")
        composeRule.onNodeWithText("Set up").performScrollTo().performClick()

        events shouldBe listOf("sign in", "set up token-123")
    }

    @Test
    fun aCancelledSignInSaysSo() {
        show(
            InternetSyncUiState(
                setup = CloudflareSetup.Finished(SetupResult.Failed(SetupFailure.SIGN_IN_CANCELLED)),
                signIn = true,
            ),
        )

        composeRule.onNodeWithText("Signing in was cancelled", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        events shouldBe listOf("dismiss")
    }

    @Test
    fun aSignInWithoutCallsSaysHowToAddTheRelay() {
        show(
            InternetSyncUiState(
                setup = CloudflareSetup.Finished(
                    SetupResult.Done("https://w.anna.workers.dev", false),
                    signedIn = true,
                ),
            ),
        )

        composeRule.onNodeWithText("sign in again and allow Calls", substring = true).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aWorkerOfOnesOwnCanStillBeEntered() {
        show(InternetSyncUiState(setup = CloudflareSetup.Ready))
        composeRule.onNodeWithText("Worker address").assertDoesNotExist()

        composeRule.onNodeWithText("Use a Worker you deployed yourself").performScrollTo().performClick()

        composeRule.onNodeWithText("Worker address").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRefusedTokenSaysWhyAndOffersToTryAgain() {
        show(InternetSyncUiState(setup = CloudflareSetup.Finished(SetupResult.Failed(SetupFailure.INVALID_TOKEN))))

        composeRule.onNodeWithText("Cloudflare doesn't accept this token. Copy it again, all of it.").performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        events shouldBe listOf("dismiss")
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
    fun screenshot_cloudflare_light() =
        screenshot(InternetSyncUiState(setup = CloudflareSetup.Ready), "consumer_internet_cloudflare_light")

    @Test
    fun screenshot_cloudflareSignIn_light() = screenshot(
        InternetSyncUiState(setup = CloudflareSetup.Ready, signIn = true),
        "consumer_internet_cloudflare_sign_in_light",
    )

    @Test
    fun screenshot_cloudflareRunning_dark() = screenshot(
        InternetSyncUiState(setup = CloudflareSetup.Running(SetupStep.UPLOAD)),
        "consumer_internet_cloudflare_running_dark",
        darkTheme = true,
    )

    @Test
    fun screenshot_reachable_dark() = screenshot(
        InternetSyncUiState("https://mediasync.anna.workers.dev", reachable = true, relay = usage),
        "consumer_internet_reachable_dark",
        darkTheme = true,
    )

    private companion object {
        const val WAIT = 5_000L
        const val REDIRECT = "https://mediasync.example/oauth/cloudflare"
    }

    /** The state the sign-in page was opened with. */
    private fun stateOf(page: String) = page.substringAfter("state=").substringBefore('&')

    private suspend fun finished() =
        withTimeout(WAIT) { viewModel.uiState.first { it?.setup is CloudflareSetup.Finished } }?.setup

    private fun screenshot(state: InternetSyncUiState, name: String, darkTheme: Boolean = false) {
        show(state, darkTheme = darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: InternetSyncUiState, accepts: Boolean = true, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                InternetSyncScreen(
                    state = state,
                    actions = InternetSyncActions(
                        save = {
                            events += "save $it"
                            accepts
                        },
                        turnOff = { events += "off" },
                        setUp = { events += "set up $it" },
                        signIn = { events += "sign in" },
                        openTokenPage = { events += "token page" },
                        dismissSetup = { events += "dismiss" },
                    ),
                    onDone = { events += "done" },
                )
            }
        }
    }
}
