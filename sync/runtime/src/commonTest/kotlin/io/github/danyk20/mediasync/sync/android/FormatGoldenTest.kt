/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.LinkSpeed
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FormatGoldenTest {

    @Test
    fun bytesAreShownInTheUnitOfTheirSize() {
        bytes(5L * 1024 * 1024 * 1024) shouldBe "5.00 GB"
        bytes(1_572_864) shouldBe "1.5 MB"
        bytes(2048) shouldBe "2.0 kB"
        bytes(12) shouldBe "12 B"
    }

    @Test
    fun timesAreShownInTenthsOfASecond() {
        since(0, 1_500) shouldBe "1.5 s"
    }

    @Test
    fun linkSpeedsAreShownInMegabytesPerSecond() {
        LinkSpeed(bytes = 3L * 1024 * 1024, micros = 1_000_000).describe() shouldBe
            "3.0 MB/s (3.0 MB in 1.0 s)"
    }
}
