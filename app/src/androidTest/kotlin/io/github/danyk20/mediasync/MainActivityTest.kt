/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.feature.consumer.R as ConsumerR
import io.github.danyk20.mediasync.feature.onboarding.R as OnboardingR
import io.github.danyk20.mediasync.feature.producer.R as ProducerR
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
        awaitText(OnboardingR.string.onboarding_role_producer_title).performClick()

        awaitText(ProducerR.string.producer_status_not_paired_title).assertIsDisplayed()
    }

    @Test
    fun firstLaunch_choosingReceives_opensConsumerHome() {
        awaitText(OnboardingR.string.onboarding_role_consumer_title).performClick()

        awaitText(ConsumerR.string.consumer_status_ready_title).assertIsDisplayed()
    }

    /**
     * Settings are read from and written to disk asynchronously, which Compose's idling doesn't track.
     * Waits until the text appears; on slow emulators the first DataStore read can take seconds.
     */
    private fun awaitText(@StringRes id: Int): SemanticsNodeInteraction {
        val text = composeRule.activity.getString(id)
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onNodeWithText(text)
    }

    private companion object {
        const val SCREEN_TIMEOUT_MILLIS = 10_000L
    }
}
