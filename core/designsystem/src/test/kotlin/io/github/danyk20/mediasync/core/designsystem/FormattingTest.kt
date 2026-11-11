/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem

import io.github.danyk20.mediasync.core.designsystem.component.formatBytes
import io.github.danyk20.mediasync.core.designsystem.component.formatMegabytes
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.Test

class FormattingTest {
    @Test
    fun sizesReadNaturally() {
        formatBytes(512, Locale.US) shouldBe "512 B"
        formatBytes(1_500, Locale.US) shouldBe "1.5 kB"
        formatBytes(145_000_000, Locale.US) shouldBe "145 MB"
        formatBytes(1_200_000_000, Locale.US) shouldBe "1.2 GB"
        formatBytes(3_400_000_000_000, Locale.US) shouldBe "3.4 TB"
    }

    @Test
    fun typedMegabytesReadAsTyped() {
        formatMegabytes(25L * 1024 * 1024, Locale.US) shouldBe "25 MB"
        formatMegabytes(512L * 1024, Locale.GERMANY) shouldBe "0,5 MB"
        formatMegabytes(10_240L * 1024 * 1024, Locale.US) shouldBe "10240 MB"
    }
}
