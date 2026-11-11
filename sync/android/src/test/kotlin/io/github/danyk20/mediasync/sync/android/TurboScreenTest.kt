/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.kotest.matchers.shouldBe
import org.junit.Test

/** When a receiving phone shows MediaSync for a Turbo link (docs/PLAN.md §8.3). */
class TurboScreenTest {
    @Test
    fun beforeAndroid13InTheBackgroundMediaSyncShowsItselfIfAllowed() {
        screenAction(sdk = 30, inForeground = false, mayShow = true) shouldBe ScreenAction.SHOW
        screenAction(sdk = 30, inForeground = false, mayShow = false) shouldBe ScreenAction.NOT_ALLOWED
    }

    @Test
    fun onScreenAlreadyOrFromAndroid13NothingIsShown() {
        screenAction(sdk = 32, inForeground = true, mayShow = true) shouldBe ScreenAction.NOTHING
        screenAction(sdk = 33, inForeground = false, mayShow = true) shouldBe ScreenAction.NOTHING
        screenAction(sdk = 36, inForeground = false, mayShow = false) shouldBe ScreenAction.NOTHING
    }
}
