/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.android.TurboAvailability
import io.kotest.matchers.shouldBe
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ConsumerHomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val receiving = ConsumerHomeUiState(
        listening = true,
        transfer = IncomingTransfer("Anna's Pixel", 12, 40, 145_000_000, 830_000_000),
        producers = listOf("Anna's Pixel", "Tom's Galaxy"),
        receivedCount = 1234,
    )
    private val events = mutableListOf<String>()

    @Test
    fun readyWithoutProducers_invitesPairing() {
        show(ConsumerHomeUiState(listening = true))

        composeRule.onNodeWithText("Ready to receive").assertIsDisplayed()
        composeRule.onNodeWithText("Add sending phone").performClick()
        events shouldBe listOf("add")
    }

    @Test
    fun receiving_showsWhoAndHowFar() {
        show(receiving)

        composeRule.onNodeWithText("Receiving from Anna's Pixel").assertIsDisplayed()
        composeRule.onNodeWithText("12 of 40 · 145 MB of 830 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Tom's Galaxy").assertIsDisplayed()
    }

    @Test
    fun offline_asksForWifi() {
        show(ConsumerHomeUiState(listening = false))

        composeRule.onNodeWithText("Not receiving").assertIsDisplayed()
    }

    @Test
    fun theScheduleIsShownAndCanBeChanged() {
        show(ConsumerHomeUiState(listening = true, schedule = SyncSchedule.EveryHours(4, LocalTime.of(1, 0))))

        composeRule.onNodeWithText("Every 4 hours from 1:00 AM").assertIsDisplayed()
        composeRule.onNodeWithText("Sync schedule").performClick()
        events shouldBe listOf("schedule")
    }

    @Test
    fun aFullStorage_isShownWithWhatsWaiting() {
        show(ConsumerHomeUiState(listening = true, storageFull = true, waitingForSpace = 40))

        composeRule.onNodeWithText("Storage full").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Free up space on this phone to keep receiving. MediaSync leaves 2 GB free for your other apps. " +
                "40 photos and videos are waiting.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Free up space").performClick()
        events shouldBe listOf("storage")
    }

    @Test
    fun aVideoTooLargeForTheFreeSpace_isExplained() {
        show(ConsumerHomeUiState(listening = true, waitingForSpace = 1))

        composeRule.onNodeWithText("Some didn't fit").assertIsDisplayed()
    }

    @Test
    fun setupCards_askAndCanBeDismissed() {
        show(ConsumerHomeUiState(listening = true, askNotifications = true, askBattery = true))

        composeRule.onNodeWithText("Open settings").performClick()
        composeRule.onAllNodesWithText("Not now")[1].performClick()
        events shouldBe listOf("settings", "dismiss NOTIFICATIONS")
    }

    @Test
    fun diagnostics_areInTheMenu() {
        show(ConsumerHomeUiState(listening = true))

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Diagnostics").performClick()
        events shouldBe listOf("diagnostics")
    }

    @Test
    fun screenshot_storageFull_light() {
        show(ConsumerHomeUiState(listening = true, storageFull = true, waitingForSpace = 40, askBattery = true))
        composeRule.onRoot().captureScreenshot("consumer_home_storage_full_light")
    }

    @Test
    fun screenshot_ready_light() {
        show(ConsumerHomeUiState(listening = true))
        composeRule.onRoot().captureScreenshot("consumer_home_ready_light")
    }

    @Test
    fun screenshot_internet_light() {
        show(
            ConsumerHomeUiState(
                listening = true,
                internetSync = true,
                relay = RelayUsage(812_000_000_000, 950_000_000_000, 1_000_000_000_000, true, null),
            ),
        )
        composeRule.onNodeWithText("Relay this month: 812 GB of 1,000 GB free").assertIsDisplayed()
        composeRule.onRoot().captureScreenshot("consumer_home_internet_light")
    }

    @Test
    fun screenshot_receiving_light() {
        show(receiving)
        composeRule.onRoot().captureScreenshot("consumer_home_receiving_light")
    }

    @Test
    fun screenshot_receiving_dark() {
        show(receiving, darkTheme = true)
        composeRule.onRoot().captureScreenshot("consumer_home_receiving_dark")
    }

    @Test
    fun aTurboRequest_asksForPermission() {
        show(ConsumerHomeUiState(listening = true, askTurbo = true, turbo = TurboAvailability.NOT_ALLOWED))

        composeRule.onNodeWithText("A sending phone asked for Turbo transfer").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        events shouldBe listOf("allow turbo")
    }

    @Test
    fun aTurboRequest_withLocationOff_leadsToItsSettings() {
        show(ConsumerHomeUiState(listening = true, askTurbo = true, turbo = TurboAvailability.LOCATION_OFF))

        composeRule.onNodeWithText("Turn on location", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Location settings").performClick()
        events shouldBe listOf("allow turbo")
    }

    @Test
    fun anOpenDirectLink_isShownAndThenWhoSendsOverIt() {
        show(ConsumerHomeUiState(listening = true, directLinkOpen = true))
        composeRule.onNodeWithText("Ready for Turbo").assertIsDisplayed()
    }

    @Test
    fun receivingOverTurbo_saysSo() {
        show(receiving.copy(directLinkOpen = true))
        composeRule.onNodeWithText("Receiving from Anna's Pixel over Turbo").assertIsDisplayed()
    }

    @Test
    fun turboReceive_isOfferedForSendingPhonesAwayFromThisWifi() {
        show(
            ConsumerHomeUiState(
                listening = true,
                producers = listOf("Anna's Pixel"),
                turbo = TurboAvailability.AVAILABLE,
            ),
        )

        composeRule.onNodeWithText("Turbo receive").assertIsDisplayed().performClick()
        events shouldBe listOf("turbo receive")
    }

    @Test
    fun turboReceive_isntOfferedWithoutSendingPhonesOrWifiDirect() {
        show(ConsumerHomeUiState(listening = true, turbo = TurboAvailability.AVAILABLE))
        composeRule.onAllNodesWithText("Turbo receive").fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun turboReceive_waitsAndCanBeStopped() {
        show(
            ConsumerHomeUiState(
                listening = true,
                producers = listOf("Anna's Pixel"),
                directLinkOpen = true,
                turboReceiving = true,
                turbo = TurboAvailability.AVAILABLE,
            ),
        )

        composeRule.onNodeWithText("Waiting for a sending phone").assertIsDisplayed()
        composeRule.onNodeWithText("Stop Turbo receive").performClick()
        events shouldBe listOf("stop turbo receive")
    }

    @Test
    fun screenshot_turboReceive_dark() {
        show(
            ConsumerHomeUiState(
                listening = true,
                producers = listOf("Anna's Pixel"),
                directLinkOpen = true,
                turboReceiving = true,
                turbo = TurboAvailability.AVAILABLE,
            ),
            darkTheme = true,
        )
        composeRule.onRoot().captureScreenshot("consumer_home_turbo_receive_dark")
    }

    @Test
    fun screenshot_turboRequest_light() {
        show(
            ConsumerHomeUiState(
                listening = true,
                producers = listOf("Anna's Pixel"),
                askTurbo = true,
                turbo = TurboAvailability.NOT_ALLOWED,
            ),
        )
        composeRule.onRoot().captureScreenshot("consumer_home_turbo_request_light")
    }

    private fun show(state: ConsumerHomeUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                ConsumerHomeScreen(
                    state = state,
                    onAddProducer = { events += "add" },
                    onChangeSchedule = { events += "schedule" },
                    onAllowNotifications = { events += "notifications" },
                    onOpenSettings = { events += "settings" },
                    onOpenStorage = { events += "storage" },
                    onDismiss = { events += "dismiss $it" },
                    onOpenDiagnostics = { events += "diagnostics" },
                    onAllowTurbo = { events += "allow turbo" },
                    onDismissTurbo = { events += "dismiss turbo" },
                    onStartTurboReceive = { events += "turbo receive" },
                    onStopTurboReceive = { events += "stop turbo receive" },
                )
            }
        }
    }
}
