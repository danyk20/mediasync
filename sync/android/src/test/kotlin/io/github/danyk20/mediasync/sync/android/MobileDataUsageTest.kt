/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What internet sync sent over mobile data, for the monthly limit (docs/PLAN.md §2.2). */
@RunWith(RobolectricTestRunner::class)
class MobileDataUsageTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var now = Instant.parse("2026-10-30T22:00:00Z")
    private val zurich = ZoneId.of("Europe/Zurich")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zurich

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    @Test
    fun itAddsUpWithinAMonthAndStartsOverInTheNext() {
        val usage = MobileDataUsage(context, clock)
        usage.add(300_000_000)
        usage.add(200_000_000)
        usage.add(-5) // Nothing.

        usage.current() shouldBe 500_000_000
        MobileDataUsage(context, clock).current() shouldBe 500_000_000 // Kept.

        now = Instant.parse("2026-10-31T23:30:00Z") // Already November in Zurich.
        usage.current() shouldBe 0
        usage.sentThisMonth.value shouldBe 0
        usage.add(1_000)
        usage.current() shouldBe 1_000
        ZoneOffset.UTC.let { MobileDataUsage(context, clock.withZone(it)).current() shouldBe 1_000 }
    }
}
