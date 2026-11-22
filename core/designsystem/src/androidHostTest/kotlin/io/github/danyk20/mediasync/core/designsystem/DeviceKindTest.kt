/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.danyk20.mediasync.core.designsystem.component.LocalIsComputer
import io.github.danyk20.mediasync.core.designsystem.component.deviceText
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_body
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_body_computer
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The words for the device a screen runs on: the phone's unless a computer sets [LocalIsComputer]. */
@RunWith(RobolectricTestRunner::class)
class DeviceKindTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aPhoneSaysPhone() {
        composeRule.setContent {
            Text(stringResource(deviceText(Res.string.reset_body, Res.string.reset_body_computer)))
        }
        composeRule.onNodeWithText("This phone forgets", substring = true).assertExists()
    }

    @Test
    fun aComputerSaysComputer() {
        composeRule.setContent {
            CompositionLocalProvider(LocalIsComputer provides true) {
                Text(stringResource(deviceText(Res.string.reset_body, Res.string.reset_body_computer)))
            }
        }
        composeRule.onNodeWithText("This computer forgets", substring = true).assertExists()
    }
}
