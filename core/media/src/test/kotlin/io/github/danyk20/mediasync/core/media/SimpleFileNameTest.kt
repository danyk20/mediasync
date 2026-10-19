/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import io.kotest.matchers.shouldBe
import org.junit.Test

class SimpleFileNameTest {

    @Test
    fun onlyTheLastDotStaysTheOthersBecomeUnderscores() {
        simpleFileName("20251018_101530.LS.mp4") shouldBe "20251018_101530_LS.mp4"
        simpleFileName("IMG.2025.06.01.jpg") shouldBe "IMG_2025_06_01.jpg"
        simpleFileName(".hidden.jpg") shouldBe "_hidden.jpg"
    }

    @Test
    fun aNameWithOneDotOrNoneStaysAsItIs() {
        simpleFileName("PXL_20251018_101530.jpg") shouldBe "PXL_20251018_101530.jpg"
        simpleFileName("README") shouldBe "README"
        simpleFileName(".jpg") shouldBe ".jpg"
        simpleFileName("") shouldBe ""
    }
}
