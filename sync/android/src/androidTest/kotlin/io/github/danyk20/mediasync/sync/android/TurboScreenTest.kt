/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** MediaSync shown for a Turbo link on a receiving phone before Android 13 (docs/PLAN.md §8.3), on API 29 (CI). */
@RunWith(AndroidJUnit4::class)
class TurboScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screen = TurboScreen(context)

    @After
    fun tearDown() {
        screen.hide()
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
    }

    @Test
    fun allowedToShowItselfMediaSyncComesOnScreenAndGoesWhenTheLinkCloses() {
        assumeTrue(TurboScreen.canNeedIt)
        // Not after going home: Android would hold the screen for 5 s.
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        TurboScreen.mayShow(context) shouldBe true

        screen.bringUp() shouldBe true
        TurboScreenActivity.current.shouldNotBeNull()

        screen.hide()
        awaitGone()
        TurboScreenActivity.current.shouldBeNull()
    }

    @Test
    fun fromAndroid13NothingIsShown() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        screen.show()
        TurboScreenActivity.current.shouldBeNull()
    }

    private fun awaitGone() {
        val until = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (TurboScreenActivity.current != null &&
            SystemClock.elapsedRealtime() < until
        ) {
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } // Waits for the command to end.
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
