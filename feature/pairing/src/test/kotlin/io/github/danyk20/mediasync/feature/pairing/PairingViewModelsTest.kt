/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.PairingApprovals
import io.github.danyk20.mediasync.sync.android.PairingResult
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import okio.ByteString.Companion.toByteString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val link = PairingLink(
        consumer = DeviceId.of(ByteArray(32) { 1 }),
        token = ByteArray(16) { 2 }.toByteString(),
        addresses = listOf("192.168.1.20:47420"),
        consumerName = "Pixel 2 XL",
    )

    // Producer side

    private val sync = mockk<ProducerSync>()

    @Test
    fun aValidCodePairsAndReportsTheConsumer() {
        coEvery { sync.pair(link) } returns PairingResult.Paired("Pixel 2 XL")
        val viewModel = ProducerPairingViewModel(sync, openedLink = null)

        viewModel.onCode(link.toString())

        viewModel.uiState.value shouldBe ProducerPairingUiState.Paired("Pixel 2 XL")
    }

    @Test
    fun theCameraReadingTheSameCodeManyTimesPairsOnce() {
        val answer = CompletableDeferred<PairingResult>()
        coEvery { sync.pair(link) } coAnswers { answer.await() }
        val viewModel = ProducerPairingViewModel(sync, openedLink = null)
        val threads = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        repeat(50) {
            threads.submit {
                start.await()
                viewModel.onCode(link.toString())
            }
        }
        start.countDown()
        threads.shutdown()
        threads.awaitTermination(5, TimeUnit.SECONDS)

        viewModel.uiState.value shouldBe ProducerPairingUiState.Pairing("Pixel 2 XL")
        answer.complete(PairingResult.Paired("Pixel 2 XL"))
        coVerify(exactly = 1) { sync.pair(link) }
    }

    @Test
    fun anOpenedLinkWaitsForConfirmationBeforePairing() {
        coEvery { sync.pair(link) } returns PairingResult.Paired("Pixel 2 XL")
        val viewModel = ProducerPairingViewModel(sync, openedLink = link.toString())

        viewModel.uiState.value shouldBe ProducerPairingUiState.Confirm(link.toString(), "Pixel 2 XL")
        coVerify(exactly = 0) { sync.pair(any()) }

        viewModel.confirm()

        viewModel.uiState.value shouldBe ProducerPairingUiState.Paired("Pixel 2 XL")
        coVerify(exactly = 1) { sync.pair(link) }
    }

    @Test
    fun anOpenedLinkThatIsntAPairingLinkIsRejected() {
        val viewModel = ProducerPairingViewModel(sync, openedLink = "mediasync://pair?v=1&broken")

        viewModel.confirm()

        viewModel.uiState.value shouldBe ProducerPairingUiState.Scanning(notAPairingCode = true)
        coVerify(exactly = 0) { sync.pair(any()) }
    }

    @Test
    fun otherCodesAreRejectedAndScanningContinues() {
        val viewModel = ProducerPairingViewModel(sync, openedLink = null)

        viewModel.onCode("https://example.com")

        viewModel.uiState.value shouldBe ProducerPairingUiState.Scanning(notAPairingCode = true)
        coVerify(exactly = 0) { sync.pair(any()) }
    }

    @Test
    fun problemsAreTranslatedForTheUser() {
        val outcomes = mapOf(
            PairingResult.Refused(PairingDecision.INVALID_TOKEN) to PairingProblem.EXPIRED,
            PairingResult.Refused(PairingDecision.TIMED_OUT) to PairingProblem.NO_ANSWER,
            PairingResult.Refused(PairingDecision.DECLINED) to PairingProblem.DECLINED,
            PairingResult.Failed(SyncFailure.UNREACHABLE) to PairingProblem.UNREACHABLE,
            PairingResult.Failed(SyncFailure.CONNECTION_LOST) to PairingProblem.CONNECTION_LOST,
            PairingResult.Failed(SyncFailure.INCOMPATIBLE) to PairingProblem.INCOMPATIBLE,
        )
        outcomes.forEach { (result, problem) ->
            coEvery { sync.pair(link) } returns result
            val viewModel = ProducerPairingViewModel(sync, openedLink = null)

            viewModel.onCode(link.toString())
            viewModel.uiState.value shouldBe ProducerPairingUiState.Failed(problem)

            viewModel.retry()
            viewModel.uiState.value shouldBe ProducerPairingUiState.Scanning()
        }
    }

    // Consumer side

    private val receiverState = MutableStateFlow(ReceiverState(listening = true, port = 47_420))
    private val approvals = PairingApprovals(timeoutMillis = 5_000)
    private val receiver = mockk<ConsumerReceiver>(relaxed = true) {
        every { state } returns receiverState
        every { pendingApproval } returns approvals.pending
        every { openPairing() } returns link
    }

    @Test
    fun theConsumerShowsAFreshCode() {
        val viewModel = ConsumerPairingViewModel(receiver)

        viewModel.uiState.value.shouldBeInstanceOf<ConsumerPairingUiState.Showing>().link shouldBe link.toString()
        verify { receiver.start() }
    }

    @Test
    fun theUsersAnswerReachesThePairingRequest() {
        val viewModel = ConsumerPairingViewModel(receiver)
        val asking = Executors.newSingleThreadExecutor().submit<Boolean?> { approvals.ask("Anna's Pixel") }
        while (viewModel.uiState.value.let { it is ConsumerPairingUiState.Showing && it.approvalFrom == null }) {
            Thread.sleep(5)
        }

        (viewModel.uiState.value as ConsumerPairingUiState.Showing).approvalFrom shouldBe "Anna's Pixel"
        viewModel.accept()

        asking.get(5, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun withoutWifiItSaysSoAndCanRetry() {
        every { receiver.openPairing() } returns null
        val viewModel = ConsumerPairingViewModel(receiver)
        viewModel.uiState.value shouldBe ConsumerPairingUiState.NoNetwork

        every { receiver.openPairing() } returns link
        viewModel.retry()

        viewModel.uiState.value.shouldBeInstanceOf<ConsumerPairingUiState.Showing>()
    }

    @Test
    fun aSuccessfulPairingIsAnnounced() {
        val viewModel = ConsumerPairingViewModel(receiver)

        receiverState.value = receiverState.value.copy(
            lastPaired = PairedPeer(DeviceId.of(ByteArray(32)), "Anna's Pixel", DeviceRole.PRODUCER),
        )

        viewModel.uiState.value shouldBe ConsumerPairingUiState.Paired("Anna's Pixel")
    }

    @Test
    fun theConsumerRouteShowsTheCode() {
        val viewModel = ConsumerPairingViewModel(receiver)

        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) { ConsumerPairingRoute(onDone = {}, viewModel = viewModel) }
        }

        composeRule.onNodeWithContentDescription("Pairing code").assertIsDisplayed()
    }
}
