/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private fun consumer(state: ConsumerPairingUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
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

    private fun producer(state: ProducerPairingUiState) {
        composeRule.setContent {
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
