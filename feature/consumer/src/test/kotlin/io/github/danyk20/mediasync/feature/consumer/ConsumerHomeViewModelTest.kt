/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsumerHomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val anna = Peer(DeviceId.of(ByteArray(32) { 1 }), "Anna's Pixel", DeviceRole.PRODUCER, 0, null, null)
    private val receiverState = MutableStateFlow(ReceiverState(listening = true, port = 47_420))
    private val receiver = mockk<ConsumerReceiver>(relaxed = true) { every { state } returns receiverState }
    private val peers = mockk<PeerStore> { every { observeAll() } returns flowOf(listOf(anna)) }
    private val received = mockk<RoomReceivedLedger> { every { observeCount() } returns flowOf(7) }

    @Test
    fun startsReceivingAndListsSendingPhones() = runTest {
        val viewModel = ConsumerHomeViewModel(receiver, peers, received)

        val state = viewModel.uiState.first { it.listening }

        verify { receiver.start() }
        state shouldBe ConsumerHomeUiState(listening = true, producers = listOf("Anna's Pixel"), receivedCount = 7)
    }

    @Test
    fun showsWhoIsSendingRightNow() = runTest {
        val viewModel = ConsumerHomeViewModel(receiver, peers, received)

        receiverState.value = receiverState.value.copy(
            transfers = mapOf(anna.id to TransferProgress(2, 5, 1_000, 9_000, "PXL_1.jpg")),
        )

        viewModel.uiState.first { it.transfer != null }.transfer shouldBe
            IncomingTransfer("Anna's Pixel", 2, 5, 1_000, 9_000)
    }

    @Test
    fun theRouteOffersAddingAPhone() {
        var added = false
        val viewModel = ConsumerHomeViewModel(receiver, peers, received)

        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                ConsumerHomeRoute(onAddProducer = { added = true }, viewModel = viewModel)
            }
        }
        composeRule.onNodeWithText("Anna's Pixel").assertIsDisplayed()
        composeRule.onNodeWithText("Add sending phone").performClick()

        added shouldBe true
    }
}
