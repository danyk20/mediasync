/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage

/** Small tolerance so anti-aliasing differences between macOS and Linux renderers don't fail CI. */
@OptIn(ExperimentalRoborazziApi::class)
private val screenshotOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
)

/**
 * Records (with `-Proborazzi.test.record=true`) or verifies a screenshot of this node
 * against `src/test/screenshots/<name>.png` in the calling module. What it shows is checked for accessibility first
 * ([assertAccessible]).
 */
fun SemanticsNodeInteraction.captureScreenshot(name: String) {
    assertAccessible()
    captureRoboImage(filePath = "src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
}
