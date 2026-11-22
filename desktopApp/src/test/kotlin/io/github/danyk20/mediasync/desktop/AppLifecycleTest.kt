/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Test

class AppLifecycleTest {

    @Test
    fun `closing the window hides it only where there is a tray`() {
        closeAction(trayAvailable = true) shouldBe CloseAction.HIDE_TO_TRAY
        closeAction(trayAvailable = false) shouldBe CloseAction.QUIT
    }

    @Test
    fun `a failing shutdown step does not stop the others`() {
        val ran = mutableListOf<String>()

        AppShutdown(listOf({ ran += "receiver" }, { error("boom") }, { ran += "database" })).run()

        ran shouldContainExactly listOf("receiver", "database")
    }

    @Test
    fun theStepsRunInOrderAndTheShutdownReturnsWhenTheyAreDone() {
        val ran = mutableListOf<String>()

        AppShutdown(listOf({ ran += "copies" }, { ran += "scope" }, { ran += "database" })).run()

        ran shouldContainExactly listOf("copies", "scope", "database")
    }

    @Test
    fun aStepThatHangsDoesNotKeepTheAppFromQuitting() {
        val release = CountDownLatch(1)
        val shutdown = AppShutdown(listOf({ release.await() }))

        val started = System.nanoTime()
        try {
            shutdown.run(limitMillis = 100)
            ((System.nanoTime() - started) / NANOS_PER_MILLI < TEST_LIMIT_MILLIS) shouldBe true
        } finally {
            release.countDown()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val TEST_LIMIT_MILLIS = 5_000L
    }
}
