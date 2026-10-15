/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SetupPrompt
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.MobileDataUsage
import io.github.danyk20.mediasync.sync.android.Pending
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.SyncHistory
import io.github.danyk20.mediasync.sync.android.SyncProblem
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProducerHomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val syncState = MutableStateFlow<ProducerState>(ProducerState.Idle())
    private var waiting = 214
    private var withheld = 0
    private var turbo = TurboAvailability.AVAILABLE
    private var lowBattery = false
    private val sync = mockk<ProducerSync>(relaxed = true) {
        every { state } returns syncState
        coEvery { pendingCount() } answers { Pending(waiting, withheld, bytes = waiting * 3_000_000L) }
        every { turboAvailability() } answers { turbo }
        every { turboBatteryLow() } answers { lowBattery }
    }
    private val library = FakeMediaLibrary()
    private val settings = FakeSettingsRepository(initialRole = DeviceRole.PRODUCER)
    private val mobileDataSent = MutableStateFlow(0L)
    private val mobileData = mockk<MobileDataUsage> { every { sentThisMonth } returns mobileDataSent }

    /** 20:00 in Zurich: the next scheduled sync is at 03:00. */
    private val clock = Clock.fixed(Instant.parse("2026-10-14T18:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val consumer = Peer(DeviceId.of(ByteArray(32)), "Pixel 2 XL", DeviceRole.CONSUMER, 0, null, null)
    private val peers = mockk<PeerStore>(relaxUnitFun = true) {
        every { observeConsumer() } returns flowOf(consumer)
        every { consumer() } returns consumer
    }
    private var missed: ZonedDateTime? = null
    private val scheduler = mockk<SyncScheduler>(relaxed = true) { every { missedSync(any()) } answers { missed } }
    private val history = SyncHistory(application)
    private var canNotify = true
    private var batteryRestricted = false
    private val setup = mockk<BackgroundSetup> {
        every { canNotify() } answers { canNotify }
        every { batteryRestricted() } answers { batteryRestricted }
    }

    private fun viewModel() =
        ProducerHomeViewModel(application, sync, scheduler, peers, library, settings, history, setup, clock, mobileData)

    @Test
    fun unpairingStopsASyncAndForgetsTheReceivingPhone() {
        viewModel().unpair()

        verify { sync.cancel() }
        verify(timeout = 5_000) { peers.forget(consumer.id) }
    }

    @Test
    fun turboStartsInTheBackgroundWhenThisPhoneCanUseIt() = runTest {
        grantLibraryAccess()
        lowBattery = true
        val viewModel = viewModel()

        val state = viewModel.uiState.first { it.pendingCount != null }
        state.turbo shouldBe TurboAvailability.AVAILABLE
        state.turboBatteryLow shouldBe true
        viewModel.startTurbo()

        verify { scheduler.syncNow(turbo = true) }
    }

    @Test
    fun turboWaitsForItsPermission() = runTest {
        grantLibraryAccess()
        turbo = TurboAvailability.NOT_ALLOWED
        val viewModel = viewModel()
        viewModel.uiState.first { it.pendingCount != null }

        viewModel.onTurboPermission(granted = false)

        viewModel.uiState.first { it.turboDeclined }
        verify(exactly = 0) { scheduler.syncNow(turbo = true) }

        // Allowed in the settings: back on the screen, it starts.
        turbo = TurboAvailability.AVAILABLE
        viewModel.onTurboPermission(granted = true)
        viewModel.uiState.first { !it.turboDeclined && it.turbo == TurboAvailability.AVAILABLE }
        verify { scheduler.syncNow(turbo = true) }
    }

    private fun grantLibraryAccess() {
        val library = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        shadowOf(application).grantPermissions(*library, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @Test
    fun withoutAccessItAsksForPhotosFirst() = runTest {
        val viewModel = viewModel()

        val state = viewModel.uiState.first { it.consumerName != null }

        state.canReadLibrary shouldBe false
        state.pendingCount shouldBe null
    }

    @Test
    fun withAccessItCountsWhatsWaiting() = runTest {
        grantLibraryAccess()
        val viewModel = viewModel()

        val state = viewModel.uiState.first { it.pendingCount != null }

        state shouldBe ProducerHomeUiState(
            consumerName = "Pixel 2 XL",
            canReadLibrary = true,
            canReadLocation = true,
            sync = ProducerState.Idle(),
            pendingCount = 214,
            pendingBytes = 642_000_000,
            turbo = TurboAvailability.AVAILABLE,
            schedule = SyncSchedule.Default,
            nextSyncAt = ZonedDateTime.parse("2026-10-15T03:00+02:00[Europe/Zurich]"),
            now = ZonedDateTime.parse("2026-10-14T20:00+02:00[Europe/Zurich]"),
        )
    }

    @Test
    fun newPhotosAreCountedWhileTheScreenIsOpen() = runTest {
        grantLibraryAccess()
        val viewModel = viewModel()
        viewModel.uiState.first { it.pendingCount == 214 }

        waiting = 215
        library.change()

        viewModel.uiState.first { it.pendingCount == 215 }
    }

    @Test
    fun choosingOtherFoldersRecounts() = runTest {
        grantLibraryAccess()
        val viewModel = viewModel()
        viewModel.uiState.first { it.pendingCount == 214 }

        waiting = 1_030
        settings.setSourceSelection(SourceSelection(SourcePreset.EVERYTHING))

        val state = viewModel.uiState.first { it.pendingCount == 1_030 }
        state.sources shouldBe SourceSelection(SourcePreset.EVERYTHING)
    }

    @Test
    fun choosingOtherPhotoDetailsRecountsWhatIsHeldBack() = runTest {
        grantLibraryAccess()
        val viewModel = viewModel()
        viewModel.uiState.first { it.pendingCount == 214 }

        waiting = 200
        withheld = 14
        settings.setPhotoDetails(PhotoDetails.REMOVE_ALL)

        val state = viewModel.uiState.first { it.withheldCount == 14 }
        state.pendingCount shouldBe 200
        state.photoDetails shouldBe PhotoDetails.REMOVE_ALL
    }

    @Test
    fun grantingAccessLaterIsPickedUp() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.first { it.consumerName != null }

        grantLibraryAccess()
        viewModel.onPermissionsChanged()

        viewModel.uiState.first { it.canReadLibrary }.canReadLocation shouldBe true
    }

    @Test
    fun buttonsReachTheSyncRuntime() {
        val viewModel = viewModel()

        viewModel.syncNow()
        viewModel.stop()

        verify { scheduler.syncNow(turbo = false) } // In the background: it carries on when the user leaves the app.
        verify { sync.cancel() }
    }

    @Test
    fun theLastSyncAndAFullReceivingPhoneAreRemembered() = runTest {
        grantLibraryAccess()
        history.recordSync(atMillis = 1_000, waitingForSpace = 12)

        val state = viewModel().uiState.first { it.consumerName != null }

        state.lastSyncMillis shouldBe 1_000
        state.waitingForSpace shouldBe 12
    }

    @Test
    fun aMissedSyncSaysWhatItRanInto() = runTest {
        grantLibraryAccess()
        missed = ZonedDateTime.parse("2026-10-14T03:00+02:00[Europe/Zurich]")
        batteryRestricted = true
        history.recordProblem(SyncProblem.UNREACHABLE)

        val state = viewModel().uiState.first { it.consumerName != null }

        state.missedSync shouldBe MissedSync(missed!!, SyncProblem.UNREACHABLE, batteryRestricted = true)
    }

    @Test
    fun itAsksForWhatBackgroundSyncsNeedUntilAnsweredWithNotNow() = runTest {
        grantLibraryAccess()
        canNotify = false
        batteryRestricted = true
        val viewModel = viewModel()
        viewModel.uiState.first { it.askNotifications && it.askBattery }

        viewModel.dismiss(SetupPrompt.BATTERY)
        viewModel.uiState.first { !it.askBattery }.askNotifications shouldBe true

        canNotify = true // Allowed.
        viewModel.onPermissionsChanged()
        viewModel.uiState.first { !it.askNotifications }
    }

    @Test
    fun noBatteryCardWhenNothingRunsInTheBackground() = runTest {
        grantLibraryAccess()
        batteryRestricted = true
        every { peers.observeConsumer() } returns flowOf(consumer.copy(schedule = SyncSchedule.Manual))

        viewModel().uiState.first { it.consumerName != null }.askBattery shouldBe false
    }

    @Test
    fun internetSyncIsOfferedOnceTheReceivingPhoneHasAWorker() = runTest {
        grantLibraryAccess()
        viewModel().uiState.first { it.consumerName != null }.internetSync shouldBe false

        every { peers.observeConsumer() } returns
            flowOf(consumer.copy(rendezvousUrl = "https://mediasync.anna.workers.dev"))
        val viewModel = viewModel()
        viewModel.uiState.first { it.consumerName != null }.internetSync shouldBe true

        viewModel.setInternetOverMobileData(true)
        settings.internetOverMobileData.value shouldBe true
        mobileDataSent.value = 700_000_000
        viewModel.setMobileDataCap(2_000_000_000)
        settings.mobileDataCapBytes.value shouldBe 2_000_000_000
        viewModel.uiState.first { it.mobileDataCap == 2_000_000_000L }.mobileDataSent shouldBe 700_000_000
        viewModel.setVideosOverRelay(true)
        settings.videosOverRelay.value shouldBe true
        viewModel.uiState.first { it.videosOverRelay }
        viewModel.uiState.first { it.internetOverMobileData } // Shown as on.
    }

    @Test
    fun theRouteShowsTheStateAndSyncs() {
        grantLibraryAccess()
        syncState.value = ProducerState.Idle(SyncResult(sent = 3))
        val viewModel = viewModel()

        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) {
                ProducerHomeRoute(onPair = {}, onChooseFolders = {}, viewModel = viewModel)
            }
        }
        composeRule.onNodeWithText("214 new photos and videos").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").performClick()

        verify { scheduler.syncNow(turbo = false) }
    }
}
