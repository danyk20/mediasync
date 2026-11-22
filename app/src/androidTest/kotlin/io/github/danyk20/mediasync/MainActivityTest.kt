/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end smoke test on a real Android build (runs on API 29 and 36 emulators in CI). */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunch_choosingSends_opensProducerHome() {
        awaitNode(ONBOARDING_SENDS).performClick()

        awaitNode(PRODUCER_NOT_PAIRED_TITLE).assertIsDisplayed()
    }

    @Test
    fun firstLaunch_choosingReceives_opensConsumerHome() {
        awaitNode(ONBOARDING_RECEIVES).performClick()

        awaitNode(CONSUMER_READY_TITLE).assertIsDisplayed()
    }

    @Test
    fun sendingPhone_openingAPairingLink_asksBeforePairing() {
        awaitNode(ONBOARDING_SENDS).performClick()
        awaitNode(PRODUCER_NOT_PAIRED_TITLE)

        val link = "mediasync://pair?v=1&id=${"a".repeat(64)}&t=AAAAAAAAAAAAAAAAAAAAAA&a=127.0.0.1%3A9&n=Test+phone"
        composeRule.activity.startActivity(
            Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(composeRule.activity.packageName),
        )

        awaitNode(PAIRING_CONFIRM_TITLE).assertIsDisplayed()
    }

    /**
     * Settings are read from and written to disk asynchronously, which Compose's idling doesn't track.
     * Waits until the text appears; on slow emulators the first DataStore read can take seconds.
     */
    private fun awaitNode(text: String): SemanticsNodeInteraction {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onNodeWithText(text)
    }

    private companion object {
        // The onboarding, pairing, producer and consumer strings are Compose resources now, which the Android `R` class no longer has.
        const val PRODUCER_NOT_PAIRED_TITLE = "Not paired yet"
        const val ONBOARDING_SENDS = "This phone sends"
        const val PAIRING_CONFIRM_TITLE = "Pair with Test phone?"
        const val ONBOARDING_RECEIVES = "This phone receives"
        const val CONSUMER_READY_TITLE = "Ready to receive"
        const val SCREEN_TIMEOUT_MILLIS = 10_000L
    }
}
