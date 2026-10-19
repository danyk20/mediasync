/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.danyk20.mediasync.connector.android.BackupOverview
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppFactory
import io.github.danyk20.mediasync.connector.testing.account
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.ReceivedNames
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import io.github.danyk20.mediasync.sync.android.TurboKeyStore
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private var turboAvailability = TurboAvailability.AVAILABLE
    private val turbo = mockk<ConsumerReceiver.Turbo>(relaxed = true) {
        every { availability() } answers { turboAvailability }
    }
    private val receiver = mockk<ConsumerReceiver>(relaxed = true) {
        every { state } returns receiverState
        every { freeBytes() } answers { freeBytes }
        every { turbo } returns this@ConsumerHomeViewModelTest.turbo
        every { relayUsage() } returns RelayUsage(812_000_000_000, 950_000_000_000, 1_000_000_000_000, true, null)
    }
    private val peers = mockk<PeerStore>(relaxUnitFun = true) { every { observeAll() } returns flowOf(listOf(anna)) }
    private val turboKeys = mockk<TurboKeyStore>(relaxUnitFun = true)
    private val received = mockk<RoomReceivedLedger> { every { observeCount() } returns flowOf(7) }
    private val settings = FakeSettingsRepository()
    private var canNotify = true
    private var batteryRestricted = false
    private val setup = mockk<BackgroundSetup> {
        every { canNotify() } answers { canNotify }
        every { batteryRestricted() } answers { batteryRestricted }
    }

    private val backupOverviews = MutableStateFlow<List<BackupOverview>>(emptyList())
    private val backup = mockk<CloudBackup> {
        every { overviews() } returns backupOverviews
        every { deviceApps } returns listOf(FakeDeviceAppFactory())
    }

    private val names = mockk<ReceivedNames> { coEvery { simplifyStored() } returns 0 }

    private fun viewModel() =
        ConsumerHomeViewModel(receiver, peers, turboKeys, received, settings, setup, backup, names)

    @Test
    fun filesReceivedBeforeGetSimpleNamesAndAgainWhenTheyAreTurnedBackOn() = runTest {
        val viewModel = viewModel()
        coVerify(timeout = WAIT_MILLIS) { names.simplifyStored() } // What was received before this version.

        viewModel.setSimpleFileNames(false)
        viewModel.uiState.first { !it.simpleFileNames }
        viewModel.setSimpleFileNames(true)

        viewModel.uiState.first { it.simpleFileNames }
        coVerify(timeout = WAIT_MILLIS, exactly = 2) { names.simplifyStored() }
    }

    @Test
    fun removingASendingPhoneForgetsItAndStopsListeningForIt() {
        viewModel().removeProducer(anna.id)

        verify(timeout = WAIT_MILLIS) { peers.forget(anna.id) }
        verify(timeout = WAIT_MILLIS) { turboKeys.renew() } // So it can't find Turbo receive either.
        verify(timeout = WAIT_MILLIS) { receiver.refreshInternet() }
    }

    @Test
    fun theBackupRowSaysWhetherAndHowMuchIsHandedOver() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.listening }.backup shouldBe BackupLine.Off

        backupOverviews.value =
            listOf(BackupOverview(account("google-photos-app").copy(displayName = "Google Photos"), today = 214))

        viewModel.uiState.first { it.backup is BackupLine.On }.backup shouldBe BackupLine.On("Google Photos", 214)
    }

    @Test
    fun aBuildWithoutBackupHasNoBackupRow() = runTest {
        every { backup.deviceApps } returns emptyList()

        viewModel().uiState.first { it.listening }.backup shouldBe null
    }

    @Test
    fun theRelayMeterIsShownWhileInternetSyncIsOn() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.listening }.relay shouldBe null
        verify(exactly = 0) { receiver.relayUsage() }

        settings.setRendezvousUrl("https://mediasync.anna.workers.dev")

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModel.uiState.first { it.relay != null } }
        }.relay?.usedBytes shouldBe 812_000_000_000
    }

    @Test
    fun startsReceivingAndListsSendingPhones() = runTest {
        val viewModel = viewModel()

        val state = viewModel.uiState.first { it.listening }

        verify { receiver.hold(any()) }
        state shouldBe ConsumerHomeUiState(
            listening = true,
            producers = listOf(PairedPhone(DeviceId.of(ByteArray(32) { 1 }), "Anna's Pixel")),
            receivedCount = 7,
            turbo = TurboAvailability.AVAILABLE,
            backup = BackupLine.Off,
        )
    }

    @Test
    fun showsWhoIsSendingRightNow() = runTest {
        val viewModel = viewModel()

        receiverState.value = receiverState.value.copy(
            transfers = mapOf(anna.id to TransferProgress(2, 5, 1_000, 9_000, "PXL_1.jpg")),
        )

        viewModel.uiState.first { it.transfer != null }.transfer shouldBe
            IncomingTransfer("Anna's Pixel", 2, 5, 1_000, 9_000, producerId = anna.id)
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
    fun aTurboRequestAsksForItsPermissionUntilAnswered() = runTest {
        turboAvailability = TurboAvailability.NOT_ALLOWED
        val viewModel = viewModel()
        viewModel.uiState.first { it.listening }

        receiverState.value = receiverState.value.copy(turboNeedsPermission = true)
        viewModel.uiState.first { it.askTurbo }

        // Allowed: the card goes, and the receiver stops asking.
        turboAvailability = TurboAvailability.AVAILABLE
        viewModel.onTurboAnswered()
        viewModel.uiState.first { !it.askTurbo && it.turbo == TurboAvailability.AVAILABLE }
        verify { turbo.permissionHandled() }
    }

    @Test
    fun turboReceiveStartsWhenThisPhoneCanUseIt() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.listening }

        viewModel.startTurboReceive()
        verify(timeout = 5_000) { turbo.startReceive() }
        receiverState.value = receiverState.value.copy(directLinkOpen = true, turboReceiving = true)
        viewModel.uiState.first { it.turboReceiving }

        viewModel.stopTurboReceive()
        verify { turbo.stopReceive() }
    }

    @Test
    fun turboReceiveNeedsItsPermissionFirst() = runTest {
        turboAvailability = TurboAvailability.NOT_ALLOWED
        val viewModel = viewModel()

        viewModel.startTurboReceive()

        verify(exactly = 0) { turbo.startReceive() }
    }

    @Test
    fun anOpenDirectLinkIsShown() = runTest {
        val viewModel = viewModel()

        receiverState.value = receiverState.value.copy(directLinkOpen = true)

        viewModel.uiState.first { it.directLinkOpen }
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
        composeRule.onNodeWithText("Anna's Pixel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add sending phone").performScrollTo().performClick()

        added shouldBe true
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
