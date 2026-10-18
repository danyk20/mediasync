/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.component.SPEED_CHART_TAG
import io.github.danyk20.mediasync.core.designsystem.component.SpeedOverview
import io.github.danyk20.mediasync.core.designsystem.component.SpeedPoint
import io.github.danyk20.mediasync.core.designsystem.component.TransferRateRow
import io.github.danyk20.mediasync.core.designsystem.component.elapsed
import io.github.danyk20.mediasync.core.designsystem.component.speedAxisTop
import io.github.danyk20.mediasync.core.designsystem.component.speedTick
import io.github.danyk20.mediasync.core.designsystem.component.timeStep
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chart of a transfer's speed over time, behind the ⓘ next to its speed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SpeedChartTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Three minutes of a transfer that slows down while Wi-Fi is busy, and recovers. */
    private val threeMinutes = (5..180).map { second ->
        val wave = 2_000_000 * sin(second / 9.0)
        val dip = if (second in 70..95) -5_000_000 else 0
        SpeedPoint(second * 1_000L, (8_000_000 + wave + dip).toLong().coerceAtLeast(300_000))
    }

    @Test
    fun theSpeedAxisClimbsInCleanStepsJustPastThePeak() {
        speedAxisTop(0) shouldBe 1_000_000 // At least 1 MB/s, in four steps of 250 kB/s.
        speedAxisTop(8_400_000) shouldBe 10_000_000 // 0 / 2.5 / 5 / 7.5 / 10 MB/s.
        speedAxisTop(12_400_000) shouldBe 20_000_000
        speedAxisTop(140_000) shouldBe 1_000_000
        speedTick(2_000_000) shouldBe "2 MB"
        speedTick(2_500_000) shouldBe "2.5 MB"
        speedTick(0) shouldBe "0"
    }

    @Test
    fun timeIsCountedFromTheStartAndLabelledAboutFourTimesAcross() {
        elapsed(102_000) shouldBe "1:42"
        elapsed(3_725_000) shouldBe "1:02:05"
        timeStep(60_000) shouldBe 15_000
        timeStep(68_000) shouldBe 30_000
        timeStep(50_000_000) shouldBe 3_600_000
    }

    @Test
    fun talkBackReadsWhatTheChartShows() {
        show(threeMinutes)

        composeRule.onNodeWithTag(SPEED_CHART_TAG).assert(
            hasContentDescription("Transfer speed over 3 min", substring = true),
        )
        composeRule.onNodeWithText("Now").assertExists()
        composeRule.onNodeWithText("Average").assertExists()
        composeRule.onNodeWithText("Peak").assertExists()
    }

    @Test
    fun beforeAFewSecondsOfTransferThereIsNothingToChartYet() {
        show(emptyList())

        composeRule.onNodeWithText("The chart starts after a few seconds of transfer.").assertExists()
    }

    @Test
    fun theInfoIconOpensTheChart() {
        var opened = 0
        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) { TransferRateRow("8.4 MB/s · about 2 min left", { opened++ }) }
        }

        composeRule.onNodeWithContentDescription("Speed over time").performClick()

        opened shouldBe 1
    }

    @Test
    fun screenshot_light() {
        show(threeMinutes)
        composeRule.onRoot().captureScreenshot("speed_chart_light")
    }

    @Test
    fun screenshot_tappedMoment_dark() {
        show(threeMinutes, darkTheme = true)
        composeRule.onNodeWithTag(SPEED_CHART_TAG).performTouchInput { click(center) }
        composeRule.onRoot().captureScreenshot("speed_chart_tapped_dark")
    }

    private fun show(points: List<SpeedPoint>, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                SpeedOverview(
                    points,
                    Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
                )
            }
        }
    }
}
