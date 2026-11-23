/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DiagnosticsTest {

    private val directory: File = Files.createTempDirectory("diagnostics").toFile()
    private val clock = Clock.fixed(Instant.parse("2026-10-14T07:30:00.250Z"), ZoneId.of("Europe/Zurich"))

    @AfterEach
    fun tearDown() {
        Diagnostics.install { _, _ -> }
        directory.deleteRecursively()
    }

    @Test
    fun aCrashIsLoggedAndWrittenBeforeTheAppEnds() {
        val log = FileDiagnosticLog(directory, clock)
        Diagnostics.install(log)
        val android = Thread.getDefaultUncaughtExceptionHandler()
        val handed = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> handed += error }
        try {
            Diagnostics.logCrashes(log::flush)

            thread(name = "setup") { throw IllegalArgumentException("Unexpected char in Authorization value") }.join()

            val entry = File(directory, "diagnostics.log").readText() // Already written: no waiting.
            entry shouldContain
                "app      Crashed on setup: IllegalArgumentException: Unexpected char in Authorization value at "
            entry shouldContain "DiagnosticsTest"
            handed.single().message shouldBe "Unexpected char in Authorization value" // Android still ends the app.
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(android)
        }
    }

    @Test
    fun manyThreadsCanLogAtOnce() {
        val log = FileDiagnosticLog(directory, clock)

        (1..8).map { t -> thread { repeat(100) { log.log(Area.APP, "thread $t entry $it") } } }.forEach { it.join() }

        log.read().lines().count { it.contains("entry") } shouldBe 800
    }

    @Test
    fun theGlobalLogCleansAndDescribesErrors() {
        val entries = mutableListOf<String>()
        Diagnostics.install { area, message -> entries += "${area.label} $message" }

        Diagnostics.log(
            Area.SEND,
            "Connection lost",
            IOException("Broken pipe at /data/user/0/x", SocketTimeoutException("Read timed out")),
        )

        entries.single() shouldStartWith "send Connection lost: IOException: Broken pipe at <path>"
        entries.single() shouldContain "SocketTimeoutException: Read timed out"
    }
}
