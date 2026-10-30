/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.sync.engine.TransferEstimate
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The ongoing notifications say how far a transfer got, how fast it goes, and how long the rest takes. */
@RunWith(RobolectricTestRunner::class)
class SyncNotificationsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = SyncNotifications(app)
    private val progress = TransferProgress(12, 40, 145_000_000, 830_000_000, "PXL_1.jpg")

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent: posts the first notification, before its pause.
    private fun shown(show: suspend () -> Unit): String = run {
        var text = ""
        runTest {
            val job = launch { show() }
            runCurrent()
            val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
            text = posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            job.cancel()
        }
        text
    }

    @Test
    fun theSendingPhoneSaysHowFastAndHowLongTheRestTakes() {
        val estimate = TransferEstimate(8_400_000, 81_500)

        shown {
            notifications.showSendingProgress(
                "Pixel 2 XL",
                MutableStateFlow(ProducerState.Syncing(progress, estimate = estimate)),
            )
        } shouldBe "12 of 40 · 8.4 MB/s · about 2 min left"
    }

    @Test
    fun theLastMinuteIsCountedInSeconds() {
        val estimate = TransferEstimate(8_400_000, 32_000)

        shown {
            notifications.showSendingProgress(
                "Pixel 2 XL",
                MutableStateFlow(ProducerState.Syncing(progress, estimate = estimate)),
            )
        } shouldBe "12 of 40 · 8.4 MB/s · about 40 s left"
    }

    @Test
    fun aStalledTransferWaitsForTheConnection() {
        shown {
            notifications.showSendingProgress(
                "Pixel 2 XL",
                MutableStateFlow(ProducerState.Syncing(progress, estimate = TransferEstimate(0, null))),
            )
        } shouldBe "12 of 40 · waiting for the connection"
    }

    @Test
    fun theReceivingPhoneSaysOnlyHowFastAsItDoesntKnowTheTotal() {
        val incoming = Incoming("Anna's Pixel", progress, TransferEstimate(1_500_000, 400_000))

        shown { notifications.showReceivingProgress(MutableStateFlow(incoming)) } shouldBe "12 of 40 · 1.5 MB/s"
    }

    @Test
    fun beforeThereIsEnoughToGoOnOnlyTheCountShows() {
        shown {
            notifications.showSendingProgress("Pixel 2 XL", MutableStateFlow(ProducerState.Syncing(progress)))
        } shouldBe "12 of 40"
    }
}
