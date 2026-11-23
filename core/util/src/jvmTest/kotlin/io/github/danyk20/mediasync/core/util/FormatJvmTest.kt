/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test

/**
 * The shared formatting, checked against the JDK formatter it replaced (the JVM is where that code still runs): proof
 * that the JVM results didn't change.
 */
class FormatJvmTest {

    @Test
    fun oneDecimalIsTheJdkFormatExceptAtTies() {
        val random = Random(14)
        var mismatches = 0
        repeat(100_000) {
            val value = random.nextDouble(0.0, 5_000.0)
            val ours = oneDecimal(value)
            val jdk = String.format(Locale.ROOT, "%.1f", value)
            if (ours != jdk) {
                mismatches++
                val scaled = value * 10
                // Only a value a floating-point step away from a tie may round differently.
                (abs(scaled - floor(scaled) - 0.5) < TIE_WINDOW) shouldBe true
            }
        }
        mismatches.toDouble() shouldBeLessThan 100.0
    }

    @Test
    fun twoDecimalsIsTheJdkFormatExceptAtTies() {
        val random = Random(14)
        var mismatches = 0
        repeat(100_000) {
            val value = random.nextDouble(0.0, 5_000.0)
            val ours = twoDecimals(value)
            val jdk = String.format(Locale.ROOT, "%.2f", value)
            if (ours != jdk) {
                mismatches++
                val scaled = value * 100
                // Only a value a floating-point step away from a tie may round differently.
                (abs(scaled - floor(scaled) - 0.5) < TIE_WINDOW) shouldBe true
            }
        }
        mismatches.toDouble() shouldBeLessThan 100.0
    }

    @Test
    fun currentTimeMillisMatchesTheSystemClock() {
        (abs(currentTimeMillis() - System.currentTimeMillis()) < 1000) shouldBe true
    }

    private companion object {
        const val TIE_WINDOW = 1e-6
    }
}
