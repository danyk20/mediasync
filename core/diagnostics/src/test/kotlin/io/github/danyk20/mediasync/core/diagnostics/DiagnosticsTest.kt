/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
    fun pathsAndAddressesNeverReachTheLog() {
        val cleaned = Sanitizer.clean(
            "Can't open content://media/external/images/media/42 at /storage/emulated/0/DCIM/Camera/PXL_1.jpg " +
                "or file:///sdcard/x.jpg; peer 192.168.1.37:47420, fe80::1c2b:3aff:fe4d%wlan0 and 2001:db8:0:0:1:2:3:4",
        )

        listOf("media/42", "DCIM", "PXL_1", "sdcard", ".37", "fe80", "2001:db8").forEach { cleaned shouldNotContain it }
        cleaned shouldContain "<content uri>"
        cleaned shouldContain "<path>"
        cleaned shouldContain "192.168.1.x:47420" // The subnet stays: same Wi-Fi or not.
    }

    @Test
    fun ordinaryMessagesStayAsTheyAre() {
        val message = "Sent 12 of 40 (3.2 MB) in 00:01:07, retry at 03:15, schedule daily@03:00, ratio 1.5"

        Sanitizer.clean(message) shouldBe message
    }

    @Test
    fun entriesAreTimeStampedOneLineEachInOrder() {
        val log = FileDiagnosticLog(directory, clock)

        log.log(Area.SEND, "first")
        log.log(Area.RECEIVE, "second\nline")

        log.recent(10) shouldContainExactly listOf(
            "2026-10-14 09:30:00.250 +0200 send     first",
            "2026-10-14 09:30:00.250 +0200 receive  second line",
        )
    }

    @Test
    fun theLogKeepsAtMostTwoFilesWorthOfTheNewestEntries() {
        val log = FileDiagnosticLog(directory, clock, maxBytes = 1_000)

        repeat(200) { log.log(Area.WORK, "entry $it") }

        val kept = log.read()
        kept.length.toLong() shouldBeLessThan 2_000
        kept.trimEnd().lines().last() shouldContain "entry 199"
        kept shouldNotContain "entry 0\n"
        directory.listFiles()!!.size shouldBe 2
    }

    @Test
    fun manyThreadsCanLogAtOnce() {
        val log = FileDiagnosticLog(directory, clock)

        (1..8).map { t -> thread { repeat(100) { log.log(Area.APP, "thread $t entry $it") } } }.forEach { it.join() }

        log.read().lines().count { it.contains("entry") } shouldBe 800
    }

    @Test
    fun clearingStartsAfresh() {
        val log = FileDiagnosticLog(directory, clock)
        log.log(Area.APP, "before")

        log.clear()
        log.log(Area.APP, "after")

        log.recent(10).single() shouldContain "after"
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
