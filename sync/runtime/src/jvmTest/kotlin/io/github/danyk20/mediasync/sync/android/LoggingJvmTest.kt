/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.Locale
import org.junit.jupiter.api.Test

class LoggingJvmTest {

    // The formatter this log used before the common code: the common text must match it for these sizes.
    private fun oldBytes(count: Long): String = when {
        count >= GB -> String.format(Locale.ROOT, "%.2f GB", count.toDouble() / GB)
        count >= MB -> String.format(Locale.ROOT, "%.1f MB", count.toDouble() / MB)
        count >= KB -> String.format(Locale.ROOT, "%.1f kB", count.toDouble() / KB)
        else -> "$count B"
    }

    @Test
    fun bytesMatchTheJvmFormatterForSampleSizes() {
        listOf(0L, 1L, 1023L, 1024L, 1536L, 1_048_576L, 3_145_728L, 123_456_789L, 5_368_709_120L, 9_999_999_999L)
            .forEach { bytes(it) shouldBe oldBytes(it) }
    }

    @Test
    fun durationsAreShownInHoursMinutesOrSeconds() {
        duration(Duration.ofMinutes(372)) shouldBe "6 h 12 min"
        duration(Duration.ofMinutes(42)) shouldBe "42 min"
        duration(Duration.ofSeconds(42)) shouldBe "42 s"
    }

    private companion object {
        const val KB = 1024L
        const val MB = KB * 1024
        const val GB = MB * 1024
    }
}
