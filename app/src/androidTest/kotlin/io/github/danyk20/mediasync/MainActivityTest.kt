/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
        composeRule.onNodeWithText(string(OnboardingR.string.onboarding_role_producer_title)).performClick()

        composeRule.onNodeWithText(string(ProducerR.string.producer_status_not_paired_title)).assertIsDisplayed()
    }

    @Test
    fun firstLaunch_choosingReceives_opensConsumerHome() {
        composeRule.onNodeWithText(string(OnboardingR.string.onboarding_role_consumer_title)).performClick()

        composeRule.onNodeWithText(string(ConsumerR.string.consumer_status_ready_title)).assertIsDisplayed()
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)
}
