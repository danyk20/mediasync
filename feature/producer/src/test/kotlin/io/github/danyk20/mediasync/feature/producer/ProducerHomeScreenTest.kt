/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.android.SyncProblem
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import io.github.danyk20.mediasync.sync.android.TurboProblem
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import java.time.ZonedDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProducerHomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val paired = ProducerHomeUiState(
        consumerName = "Pixel 2 XL",
        canReadLibrary = true,
        canReadLocation = true,
        schedule = SyncSchedule.Default,
        nextSyncAt = ZonedDateTime.parse("2026-10-15T03:00+02:00[Europe/Zurich]"),
        now = ZonedDateTime.parse("2026-10-14T20:00+02:00[Europe/Zurich]"),
    )
    private val syncing = paired.copy(
        sync = ProducerState.Syncing(TransferProgress(12, 40, 145_000_000, 830_000_000, "PXL_1.jpg")),
    )
    private val events = mutableListOf<String>()

    @Test
    fun notPaired_offersPairing() {
        show(ProducerHomeUiState())

        composeRule.onNodeWithText("Not paired yet").assertIsDisplayed()
        composeRule.onNodeWithText("Pair with receiving phone").performClick()
        events shouldBe listOf("pair")
    }

    @Test
    fun withoutPhotoAccess_asksForIt() {
        show(paired.copy(canReadLibrary = false))

        composeRule.onNodeWithText("Allow access to photos").assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").performClick()
        events shouldBe listOf("grant")
    }

    @Test
    fun pendingPhotos_canBeSentNow() {
        show(paired.copy(pendingCount = 214))

        composeRule.onNodeWithText("214 new photos and videos").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").performClick()
        events shouldBe listOf("sync")
    }

    @Test
    fun syncing_showsProgressAndCanStop() {
        show(syncing)

        composeRule.onNodeWithText("Sending to Pixel 2 XL").assertIsDisplayed()
        composeRule.onNodeWithText("12 of 40 · 145 MB of 830 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").performClick()
        events shouldBe listOf("stop")
    }

    @Test
    fun failures_explainWhatToDo() {
        show(paired.copy(sync = ProducerState.Failed(SyncFailure.NOT_ALLOWED)))

        composeRule.onNodeWithText("Pixel 2 XL no longer recognises this phone. Pair them again.").assertIsDisplayed()
    }

    @Test
    fun missingLocationAccess_isPointedOut() {
        show(paired.copy(canReadLocation = false, pendingCount = 0))

        composeRule.onNodeWithText("Keep locations").assertIsDisplayed()
    }

    @Test
    fun theNextSyncFollowsTheReceivingPhonesSchedule() {
        show(paired.copy(pendingCount = 0))
        composeRule.onNodeWithText("Next sync tomorrow at 3:00 AM, while charging.").assertIsDisplayed()
    }

    @Test
    fun chargingAndManualSchedulesAreExplained() {
        show(paired.copy(pendingCount = 0, schedule = SyncSchedule.WhileCharging, nextSyncAt = null))
        composeRule.onNodeWithText("Syncs whenever this phone is charging and has new photos.").assertIsDisplayed()
    }

    @Test
    fun aManualScheduleIsExplained() {
        show(paired.copy(pendingCount = 0, schedule = SyncSchedule.Manual, nextSyncAt = null))
        composeRule.onNodeWithText("Syncs only when you tap Sync now.").assertIsDisplayed()
    }

    @Test
    fun theChosenFoldersAreShownAndCanBeChanged() {
        show(paired.copy(pendingCount = 0))

        composeRule.onNodeWithText("Camera only").assertIsDisplayed()
        composeRule.onNodeWithText("Folders to send").performClick()
        events shouldBe listOf("folders")
    }

    @Test
    fun handPickedFoldersAreSummarisedByName() {
        val folders =
            setOf("DCIM/Camera/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "Pictures/Instagram/")
        show(paired.copy(pendingCount = 0, sources = SourceSelection.of(folders)))

        composeRule.onNodeWithText("Camera, Instagram and 1 more").assertIsDisplayed()
    }

    @Test
    fun withoutFolders_nothingIsSent() {
        show(paired.copy(pendingCount = 0, sources = SourceSelection.of(emptySet())))

        composeRule.onNodeWithText("No folders chosen").assertIsDisplayed()
        composeRule.onNodeWithText("None chosen").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sync now").fetchSemanticsNodes() shouldBe emptyList()
    }

    @Test
    fun theLastSyncIsShown() {
        show(paired.copy(pendingCount = 0, lastSyncMillis = millis("2026-10-14T03:04+02:00")))

        composeRule.onNodeWithText("Last sync today at 3:04 AM").assertIsDisplayed()
    }

    @Test
    fun aMissedSync_saysWhy() {
        show(paired.copy(pendingCount = 0, missedSync = missed(SyncProblem.UNREACHABLE)))

        composeRule.onNodeWithText("The sync planned for today at 3:00 AM didn't run").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Pixel 2 XL couldn't be found on the Wi-Fi. This phone tries again while charging.",
        ).assertIsDisplayed()
    }

    @Test
    fun aMissedSyncOnALowBattery_saysSo() {
        show(paired.copy(pendingCount = 0, missedSync = missed(SyncProblem.LOW_BATTERY)))

        composeRule.onNodeWithText(
            "The battery was below 20%. This phone tries again once it has charged more.",
        ).assertIsDisplayed()
    }

    @Test
    fun aMissedSyncWithRestrictedBattery_leadsToTheSettings() {
        show(paired.copy(pendingCount = 0, missedSync = missed(null, batteryRestricted = true)))

        composeRule.onNodeWithText("Open settings").performClick()
        events shouldBe listOf("settings")
    }

    @Test
    fun aFullReceivingPhone_isPointedOut() {
        show(paired.copy(pendingCount = 12, waitingForSpace = 12))

        composeRule.onNodeWithText("Pixel 2 XL is full").assertIsDisplayed()
        composeRule.onNodeWithText(
            "12 photos and videos are waiting. They're sent once there's space on Pixel 2 XL.",
        ).assertIsDisplayed()
    }

    @Test
    fun whileSending_noticesWait() {
        show(syncing.copy(waitingForSpace = 12, missedSync = missed(null)))

        composeRule.onAllNodesWithText("Pixel 2 XL is full").fetchSemanticsNodes() shouldBe emptyList()
    }

    @Test
    fun photoDetails_areShownAndCanBeChanged() {
        show(paired.copy(pendingCount = 0, photoDetails = PhotoDetails.REMOVE_LOCATION))

        composeRule.onNodeWithText("Remove location").assertIsDisplayed()
        composeRule.onNodeWithText("Photo details").performClick()
        events shouldBe listOf("details")
    }

    @Test
    fun heldBackFiles_pointToPhotoDetails() {
        show(paired.copy(pendingCount = 0, withheldCount = 3, photoDetails = PhotoDetails.REMOVE_ALL))

        composeRule.onNodeWithText("3 photos and videos aren't sent").assertIsDisplayed()
        composeRule.onAllNodesWithText("Photo details")[0].performClick()
        events shouldBe listOf("details")
    }

    @Test
    fun diagnostics_areInTheMenu() {
        show(paired.copy(pendingCount = 0))

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Diagnostics").performClick()
        events shouldBe listOf("diagnostics")
    }

    @Test
    fun setupCards_askAndCanBeDismissed() {
        show(paired.copy(pendingCount = 0, askNotifications = true, askBattery = true))

        composeRule.onNodeWithText("Let syncs run at night").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        composeRule.onAllNodesWithText("Not now")[0].performClick()
        events shouldBe listOf("notifications", "dismiss BATTERY")
    }

    @Test
    fun mobileDataIsOfferedOnlyWithInternetSync() {
        show(paired.copy(pendingCount = 3, internetSync = true))

        composeRule.onNodeWithText("Sync over mobile data").performScrollTo().performClick()

        events shouldBe listOf("mobile data true")
    }

    @Test
    fun noMobileDataWithoutInternetSync() {
        show(paired.copy(pendingCount = 3))

        composeRule.onNodeWithText("Sync over mobile data").assertDoesNotExist()
    }

    @Test
    fun screenshot_internet_dark() = screenshot(
        paired.copy(pendingCount = 3, internetSync = true, internetOverMobileData = true),
        "producer_home_internet_dark",
        darkTheme = true,
    )

    @Test
    fun screenshot_notPaired_light() = screenshot(ProducerHomeUiState(), "producer_home_not_paired_light")

    @Test
    fun screenshot_pending_light() = screenshot(paired.copy(pendingCount = 214), "producer_home_pending_light")

    @Test
    fun screenshot_syncing_light() = screenshot(syncing, "producer_home_syncing_light")

    @Test
    fun screenshot_syncing_dark() = screenshot(syncing, "producer_home_syncing_dark", darkTheme = true)

    @Test
    fun screenshot_failed_light() = screenshot(
        paired.copy(sync = ProducerState.Failed(SyncFailure.UNREACHABLE)),
        "producer_home_failed_light",
    )

    @Test
    fun screenshot_noFolders_light() = screenshot(
        paired.copy(pendingCount = 0, sources = SourceSelection.of(emptySet())),
        "producer_home_no_folders_light",
    )

    @Test
    fun screenshot_missed_light() = screenshot(
        paired.copy(pendingCount = 37, missedSync = missed(SyncProblem.UNREACHABLE), askNotifications = true),
        "producer_home_missed_light",
    )

    @Test
    fun screenshot_setup_dark() = screenshot(
        paired.copy(pendingCount = 0, lastSyncMillis = millis("2026-10-14T03:04+02:00"), askBattery = true),
        "producer_home_setup_dark",
        darkTheme = true,
    )

    @Test
    fun screenshot_withheld_light() = screenshot(
        paired.copy(pendingCount = 0, withheldCount = 3, photoDetails = PhotoDetails.REMOVE_ALL),
        "producer_home_withheld_light",
    )

    @Test
    fun screenshot_receiverFull_light() = screenshot(
        paired.copy(pendingCount = 12, waitingForSpace = 12),
        "producer_home_receiver_full_light",
    )

    @Test
    fun turbo_isOfferedWhileThereIsSomethingToSend() {
        show(paired.copy(pendingCount = 12, pendingBytes = 40_000_000, turbo = TurboAvailability.AVAILABLE))

        composeRule.onNodeWithText("Turbo transfer").assertIsDisplayed()
        composeRule.onNodeWithText("Start Turbo").performClick()
        events shouldBe listOf("turbo")
    }

    @Test
    fun turbo_isSuggestedForABigBacklogAndWarnsOnALowBattery() {
        show(
            paired.copy(
                pendingCount = 2_400,
                pendingBytes = 4_300_000_000,
                turbo = TurboAvailability.AVAILABLE,
                turboBatteryLow = true,
            ),
        )

        composeRule.onNodeWithText("4.3 GB waiting: try Turbo transfer").assertIsDisplayed()
        composeRule.onNodeWithText("The battery is low", substring = true).assertIsDisplayed()
    }

    @Test
    fun turbo_isntOfferedWithoutWifiDirectOrWithNothingToSend() {
        show(paired.copy(pendingCount = 12, turbo = TurboAvailability.UNSUPPORTED))
        composeRule.onAllNodesWithText("Start Turbo").fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun turbo_saysWhatItNeedsFirst() {
        show(paired.copy(pendingCount = 12, turbo = TurboAvailability.LOCATION_OFF))

        composeRule.onNodeWithText("Turn on location first", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Location settings").performClick()
        events shouldBe listOf("location")
    }

    @Test
    fun turbo_declinedLeadsToTheSettings() {
        show(paired.copy(pendingCount = 12, turbo = TurboAvailability.NOT_ALLOWED, turboDeclined = true))

        composeRule.onNodeWithText("Allow it in MediaSync's settings", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").performClick()
        events shouldBe listOf("settings")
    }

    @Test
    fun turbo_showsItsProgressAndWhyItWasntUsed() {
        show(paired.copy(sync = ProducerState.SettingUpTurbo))
        composeRule.onNodeWithText("Setting up Turbo with Pixel 2 XL…").assertIsDisplayed()
    }

    @Test
    fun turbo_sendingSaysSo() {
        show(syncing.copy(sync = ProducerState.Syncing(TransferProgress(1, 9, 1, 9, null), turbo = true)))
        composeRule.onNodeWithText("Sending to Pixel 2 XL over Turbo").assertIsDisplayed()
    }

    @Test
    fun internet_sendingSaysSo() {
        show(syncing.copy(sync = ProducerState.Syncing(TransferProgress(1, 9, 1, 9, null), internet = true)))
        composeRule.onNodeWithText("Sending to Pixel 2 XL over the internet").assertIsDisplayed()
    }

    @Test
    fun turbo_whyItWasntUsed() {
        show(paired.copy(sync = ProducerState.Idle(turboProblem = TurboProblem.RECEIVER_NOT_ALLOWED)))

        composeRule.onNodeWithText("Turbo wasn't used").assertIsDisplayed()
        composeRule.onNodeWithText("Turbo needs your OK on Pixel 2 XL", substring = true).assertIsDisplayed()
    }

    @Test
    fun turbo_awayFromTheWifiPointsToTurboReceive() {
        show(paired.copy(sync = ProducerState.Failed(SyncFailure.TURBO_UNREACHABLE)))
        composeRule.onNodeWithText("tap Turbo receive", substring = true).assertIsDisplayed()
    }

    @Test
    fun turbo_awayFromTheWifiNeedsOneSyncOnItFirst() {
        show(paired.copy(sync = ProducerState.Failed(SyncFailure.TURBO_NOT_SET_UP)))
        composeRule.onNodeWithText("sync once on the same Wi-Fi first", substring = true).assertIsDisplayed()
    }

    @Test
    fun screenshot_turbo_light() = screenshot(
        paired.copy(pendingCount = 2_400, pendingBytes = 4_300_000_000, turbo = TurboAvailability.AVAILABLE),
        "producer_home_turbo_light",
    )

    @Test
    fun screenshot_turboSending_dark() = screenshot(
        syncing.copy(
            sync = ProducerState.Syncing(TransferProgress(300, 2_400, 1_200_000_000, 4_300_000_000, null), true),
        ),
        "producer_home_turbo_sending_dark",
        darkTheme = true,
    )

    @Test
    fun screenshot_turboNotUsed_light() = screenshot(
        paired.copy(sync = ProducerState.Idle(turboProblem = TurboProblem.NO_LINK)),
        "producer_home_turbo_not_used_light",
    )

    private fun millis(text: String) = ZonedDateTime.parse("$text[Europe/Zurich]").toInstant().toEpochMilli()

    private fun missed(problem: SyncProblem?, batteryRestricted: Boolean = false) =
        MissedSync(ZonedDateTime.parse("2026-10-14T03:00+02:00[Europe/Zurich]"), problem, batteryRestricted)

    private fun screenshot(state: ProducerHomeUiState, name: String, darkTheme: Boolean = false) {
        show(state, darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: ProducerHomeUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                ProducerHomeScreen(
                    state = state,
                    onPair = { events += "pair" },
                    onChooseFolders = { events += "folders" },
                    onSyncNow = { events += "sync" },
                    onStop = { events += "stop" },
                    onGrantAccess = { events += "grant" },
                    onOpenSettings = { events += "settings" },
                    onAllowNotifications = { events += "notifications" },
                    onDismiss = { events += "dismiss $it" },
                    onChooseDetails = { events += "details" },
                    onInternetOverMobileData = { events += "mobile data $it" },
                    onOpenDiagnostics = { events += "diagnostics" },
                    onStartTurbo = { events += "turbo" },
                    onOpenLocationSettings = { events += "location" },
                )
            }
        }
    }
}
