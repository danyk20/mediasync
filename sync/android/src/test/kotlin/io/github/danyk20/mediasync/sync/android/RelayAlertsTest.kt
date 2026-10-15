/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The receiving phone's user hears when the relay allowance runs low (docs/PLAN.md §8.5), once per level a month. */
@RunWith(RobolectricTestRunner::class)
class RelayAlertsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val alerts = RelayAlerts(context)
    private val october = Instant.parse("2026-11-01T00:00:00Z")
    private val november = Instant.parse("2026-12-01T00:00:00Z")

    init {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun usage(gigabytes: Long, resetsAt: Instant = october, available: Boolean = true) =
        RelayUsage(gigabytes * GB, limitBytes = 950 * GB, freeBytes = 1_000 * GB, available, resetsAt)

    private fun titles() = notifications.allNotifications.map {
        it.extras.getString(android.app.Notification.EXTRA_TITLE)
    }

    @Test
    fun theLevelsFollowTheFreeAllowanceAndTheBudget() {
        usage(799).level shouldBe RelayLevel.FINE
        usage(800).level shouldBe RelayLevel.HIGH // 80 % of the free 1,000 GB.
        usage(902).level shouldBe RelayLevel.HIGH
        usage(903).level shouldBe RelayLevel.NEARLY_USED_UP // 95 % of the 950 GB budget.
        usage(950).level shouldBe RelayLevel.USED_UP // The budget: the relay stops there.
        usage(10, available = false).level shouldBe RelayLevel.USED_UP
    }

    @Test
    fun eachLevelIsToldOnceAMonth() {
        alerts.check(usage(500))
        titles() shouldBe emptyList()

        alerts.check(usage(812))
        alerts.check(usage(830))
        titles() shouldBe listOf("80 % of the relay allowance used")

        alerts.check(usage(951))
        titles() shouldBe listOf("The relay allowance is used up") // The same notification, updated.
        notifications.size() shouldBe 1

        alerts.check(usage(40, november))
        alerts.check(usage(805, november))
        titles() shouldBe listOf("80 % of the relay allowance used")
    }

    @Test
    fun withoutNotificationsNothingIsPostedNorCountedAsTold() {
        notifications.setNotificationsEnabled(false)
        alerts.check(usage(960))

        notifications.size() shouldBe 0
        notifications.setNotificationsEnabled(true)
        alerts.check(usage(960))
        notifications.size() shouldBe 1
    }

    private companion object {
        const val GB = 1_000_000_000L
    }
}
