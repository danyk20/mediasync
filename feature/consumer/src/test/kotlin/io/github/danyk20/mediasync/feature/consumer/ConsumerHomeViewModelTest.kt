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
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
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
    private var freeBytes = 20L * 1024 * 1024 * 1024
    private val receiver = mockk<ConsumerReceiver>(relaxed = true) {
        every { state } returns receiverState
        every { freeBytes() } answers { freeBytes }
    }
    private val peers = mockk<PeerStore> { every { observeAll() } returns flowOf(listOf(anna)) }
    private val received = mockk<RoomReceivedLedger> { every { observeCount() } returns flowOf(7) }
    private val settings = FakeSettingsRepository()
    private var canNotify = true
    private var batteryRestricted = false
    private val setup = mockk<BackgroundSetup> {
        every { canNotify() } answers { canNotify }
        every { batteryRestricted() } answers { batteryRestricted }
    }

    private fun viewModel() = ConsumerHomeViewModel(receiver, peers, received, settings, setup)

    @Test
    fun startsReceivingAndListsSendingPhones() = runTest {
        val viewModel = viewModel()

        val state = viewModel.uiState.first { it.listening }

        verify { receiver.hold(any()) }
        state shouldBe ConsumerHomeUiState(listening = true, producers = listOf("Anna's Pixel"), receivedCount = 7)
    }

    @Test
    fun showsWhoIsSendingRightNow() = runTest {
        val viewModel = viewModel()

        receiverState.value = receiverState.value.copy(
            transfers = mapOf(anna.id to TransferProgress(2, 5, 1_000, 9_000, "PXL_1.jpg")),
        )

        viewModel.uiState.first { it.transfer != null }.transfer shouldBe
            IncomingTransfer("Anna's Pixel", 2, 5, 1_000, 9_000)
    }

    @Test
    fun aFullStorageIsNoticedWhenASyncRunsIntoIt() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.listening }.storageFull shouldBe false

        freeBytes = 0
        receiverState.value = receiverState.value.copy(waitingForSpace = 40)

        val state = viewModel.uiState.first { it.storageFull }
        state.waitingForSpace shouldBe 40
    }

    @Test
    fun freeingUpSpaceIsNoticedWhenTheUserComesBack() = runTest {
        freeBytes = 0
        val viewModel = viewModel()
        viewModel.uiState.first { it.storageFull }

        freeBytes = 5L * 1024 * 1024 * 1024
        viewModel.refresh()

        viewModel.uiState.first { !it.storageFull }
    }

    @Test
    fun itAsksForWhatReceiveWindowsNeedUntilAnsweredWithNotNow() = runTest {
        canNotify = false
        batteryRestricted = true
        val viewModel = viewModel()
        viewModel.uiState.first { it.askNotifications && it.askBattery }

        viewModel.dismiss(SetupPrompt.NOTIFICATIONS)
        viewModel.uiState.first { !it.askNotifications }.askBattery shouldBe true

        settings.setSyncSchedule(SyncSchedule.Manual) // Nothing runs in the background.
        viewModel.uiState.first { !it.askBattery }
    }

    @Test
    fun theRouteOffersAddingAPhone() {
        var added = false
        val viewModel = viewModel()

        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                ConsumerHomeRoute(onAddProducer = { added = true }, onChangeSchedule = {}, viewModel = viewModel)
            }
        }
        composeRule.onNodeWithText("Anna's Pixel").assertIsDisplayed()
        composeRule.onNodeWithText("Add sending phone").performClick()

        added shouldBe true
    }
}
