/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.component.LocalIsComputer
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Underscored test names and fixture numbers: detekt scans every source set but src/test.
@Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PairingScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val code = ConsumerPairingUiState.Showing(
        link = "mediasync://pair?v=1&id=${"ab".repeat(
            32,
        )}&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420&n=Pixel+2+XL",
        deviceName = "Pixel 2 XL",
    )

    @Test
    fun consumer_showsTheCodeAndCanShareIt() {
        consumer(code)

        composeRule.onNodeWithContentDescription("Pairing code").assertIsDisplayed()
        composeRule.onNodeWithText("Pixel 2 XL").assertIsDisplayed()
        composeRule.onNodeWithText("Share pairing link").performClick()
        events shouldBe listOf("share:${code.link}")
    }

    @Test
    fun consumer_onAComputer_copiesTheLinkAndSaysSo() {
        composeRule.mainClock.autoAdvance = false
        consumer(code, computer = true)

        composeRule.onNodeWithContentDescription("Pairing code").assertIsDisplayed()
        composeRule.onNodeWithText("Share pairing link").assertDoesNotExist()
        tap("Copy link")
        events shouldBe listOf("share:${code.link}")
        composeRule.onNodeWithText("Link copied").assertIsDisplayed()
    }

    @Test
    fun consumer_onAComputer_linkCopiedGoesBackToCopyLinkAfterThreeSeconds() {
        composeRule.mainClock.autoAdvance = false
        consumer(code, computer = true)

        tap("Copy link")
        composeRule.onNodeWithText("Link copied").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(2_999)
        composeRule.onNodeWithText("Link copied").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(1)
        composeRule.onNodeWithText("Copy link").assertIsDisplayed()
        composeRule.onNodeWithText("Link copied").assertDoesNotExist()
    }

    @Test
    fun consumer_onAComputer_aSecondCopyRestartsTheThreeSeconds() {
        composeRule.mainClock.autoAdvance = false
        consumer(code, computer = true)

        tap("Copy link")
        composeRule.mainClock.advanceTimeBy(2_000)
        tap("Link copied")
        // The first copy's three seconds would have ended by now; the second copy's are still running.
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithText("Link copied").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithText("Copy link").assertIsDisplayed()
    }

    @Test
    fun consumer_onAPhone_neverSaysLinkCopied() {
        composeRule.mainClock.autoAdvance = false
        consumer(code)

        tap("Share pairing link")
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onNodeWithText("Share pairing link").assertIsDisplayed()
        composeRule.onNodeWithText("Link copied").assertDoesNotExist()
    }

    @Test
    fun consumer_asksBeforeAllowingAPhone() {
        consumer(code.copy(approvalFrom = "Anna's Pixel"))

        composeRule.onNodeWithText("Allow Anna's Pixel?").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        events shouldBe listOf("accept")
    }

    @Test
    fun consumer_canRefuseAPhone() {
        consumer(code.copy(approvalFrom = "Unknown phone"))

        composeRule.onNodeWithText("Don't allow").performClick()
        events shouldBe listOf("decline")
    }

    @Test
    fun consumer_withoutWifi_explainsWhy() {
        consumer(ConsumerPairingUiState.NoNetwork)

        composeRule.onNodeWithText("Connect to Wi-Fi").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        events shouldBe listOf("retry")
    }

    @Test
    fun producer_withoutCamera_offersPasting() {
        producer(ProducerPairingUiState.Scanning())

        composeRule.onNodeWithText(
            "Allow the camera to scan the code, or paste the pairing link instead.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Paste pairing link").performClick()
        events shouldBe listOf("paste")
    }

    @Test
    fun producer_onAComputer_showsNoCameraAndPastes() {
        producer(ProducerPairingUiState.Scanning(), computer = true)

        composeRule.onNodeWithText("Pair with receiving device").assertIsDisplayed()
        composeRule.onNodeWithText("Allow the camera", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Paste the receiver's link").performClick()
        events shouldBe listOf("paste")
    }

    @Test
    fun producer_onAComputer_saysWhenTheClipboardHoldsNoLink() {
        producer(ProducerPairingUiState.Scanning(notAPairingCode = true), computer = true)

        composeRule.onNodeWithText(
            "The clipboard holds no MediaSync pairing link. Copy it on the receiving device first.",
        )
            .assertIsDisplayed()
    }

    @Test
    fun producer_rejectsOtherCodes() {
        producer(ProducerPairingUiState.Scanning(notAPairingCode = true))

        composeRule.onNodeWithText("That isn't a MediaSync pairing code.").assertIsDisplayed()
    }

    @Test
    fun producer_explainsEveryProblem() {
        producer(ProducerPairingUiState.Failed(PairingProblem.EXPIRED))

        composeRule.onNodeWithText(
            "This code has expired or was already used. Show a new one on the receiving phone.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        events shouldBe listOf("retry")
    }

    @Test
    fun producer_asksBeforePairingFromAnOpenedLink() {
        producer(ProducerPairingUiState.Confirm(code.link, "Pixel 2 XL"))

        composeRule.onNodeWithText("Pair with Pixel 2 XL?").assertIsDisplayed()
        composeRule.onNodeWithText("Pair").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        events shouldBe listOf("confirm", "done")
    }

    @Test
    fun screenshot_producerConfirm_light() {
        producer(ProducerPairingUiState.Confirm(code.link, "Pixel 2 XL"))
        composeRule.onRoot().captureScreenshot("pairing_producer_confirm_light")
    }

    @Test
    fun screenshot_consumerCode_light() {
        consumer(code)
        composeRule.onRoot().captureScreenshot("pairing_consumer_code_light")
    }

    @Test
    fun screenshot_consumerApproval_dark() {
        consumer(code.copy(approvalFrom = "Anna's Pixel"), darkTheme = true)
        composeRule.onRoot().captureScreenshot("pairing_consumer_approval_dark")
    }

    @Test
    fun screenshot_producerWaiting_light() {
        producer(ProducerPairingUiState.Pairing("Pixel 2 XL"))
        composeRule.onRoot().captureScreenshot("pairing_producer_waiting_light")
    }

    @Test
    fun screenshot_producerPaired_light() {
        producer(ProducerPairingUiState.Paired("Pixel 2 XL"))
        composeRule.onRoot().captureScreenshot("pairing_producer_paired_light")
    }

    /** Clicks, then runs one frame: with the test clock paused, the click's recomposition waits for a frame. */
    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performClick()
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun consumer(state: ConsumerPairingUiState, darkTheme: Boolean = false, computer: Boolean = false) {
        composeRule.setContent {
            CompositionLocalProvider(LocalIsComputer provides computer) {
                MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                    ConsumerPairingScreen(
                        state = state,
                        onShareLink = { events += "share:$it" },
                        onAccept = { events += "accept" },
                        onDecline = { events += "decline" },
                        onRetry = { events += "retry" },
                        onDone = { events += "done" },
                    )
                }
            }
        }
    }

    private fun producer(state: ProducerPairingUiState, computer: Boolean = false) {
        composeRule.setContent {
            CompositionLocalProvider(LocalIsComputer provides computer) {
                MediaSyncTheme(dynamicColor = false) {
                    ProducerPairingScreen(
                        state = state,
                        camera = null,
                        onPasteLink = { events += "paste" },
                        onConfirm = { events += "confirm" },
                        onRetry = { events += "retry" },
                        onDone = { events += "done" },
                    )
                }
            }
        }
    }
}
