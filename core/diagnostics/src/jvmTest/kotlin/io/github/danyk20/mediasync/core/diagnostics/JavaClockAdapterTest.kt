/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

class JavaClockAdapterTest {

    @Test
    fun theJavaClockStampsEntriesInItsZone() {
        val directory = Files.createTempDirectory("diagnostics-clock").toFile()
        try {
            val clock = Clock.fixed(Instant.parse("2026-10-14T07:30:00.250Z"), ZoneId.of("Europe/Zurich"))
            val log = FileDiagnosticLog(directory, clock)

            log.log(Area.SEND, "x")
            log.drain()

            File(directory, "diagnostics.log").readText() shouldBe "2026-10-14 09:30:00.250 +0200 send     x\n"
        } finally {
            directory.deleteRecursively()
        }
    }
}
