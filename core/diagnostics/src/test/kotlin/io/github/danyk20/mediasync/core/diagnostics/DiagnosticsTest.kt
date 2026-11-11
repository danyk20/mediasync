/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
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
    fun tokensNeverReachTheLog() {
        val cleaned = Sanitizer.clean("Unexpected char 0x0a at 7 in header value: Bearer cfut_abc123-XYZ, retrying")

        cleaned shouldNotContain "cfut_abc123"
        cleaned shouldContain "Bearer <token>, retrying"
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
    fun theLogRollsOverDroppingTheOldestEntriesFirst() {
        val log = FileDiagnosticLog(directory, clock, maxBytes = 10_000)

        repeat(1_000) {
            log.log(Area.WORK, "entry $it")
            log.flush()
            stored() shouldBeLessThanOrEqual 10_000
        }

        val kept = log.recent(Int.MAX_VALUE).map { it.substringAfter("entry ").toInt() }
        kept.last() shouldBe 999
        kept shouldBe (kept.first()..999).toList() // The newest, in order, with nothing missing in between.
        stored() shouldBeGreaterThan 8_000 // At most about a tenth is dropped at once.
        directory.listFiles()!!.size shouldBeLessThanOrEqual 11
    }

    @Test
    fun aSmallerLimitDropsTheOldestEntriesAtOnce() {
        val log = FileDiagnosticLog(directory, clock, maxBytes = 100_000)
        repeat(1_000) { log.log(Area.WORK, "entry $it") }
        log.flush()
        stored() shouldBeGreaterThan 40_000

        log.limitTo(5_000)

        log.read().length shouldBeLessThanOrEqual 5_000
        stored() shouldBeLessThanOrEqual 5_000
        log.recent(1).single() shouldContain "entry 999"
        log.read() shouldNotContain "entry 0\n"

        repeat(500) { log.log(Area.WORK, "more $it") }
        log.flush()
        stored() shouldBeLessThanOrEqual 5_000
        log.recent(1).single() shouldContain "more 499"
    }

    @Test
    fun aLargerLimitKeepsWhatsThere() {
        val log = FileDiagnosticLog(directory, clock, maxBytes = 5_000)
        repeat(200) { log.log(Area.WORK, "entry $it") }
        val before = log.read()

        log.limitTo(50_000)
        repeat(200) { log.log(Area.WORK, "more $it") }

        log.read() shouldStartWith before
        stored() shouldBeGreaterThan 10_000
    }

    @Test
    fun anEntryLargerThanTheWholeLogIsntKept() {
        val log = FileDiagnosticLog(directory, clock, maxBytes = 50)
        repeat(3) { log.log(Area.WORK, "a line longer than fifty bytes, number $it") }

        log.read() shouldBe ""
        stored() shouldBe 0
    }

    @Test
    fun aLogFromAnEarlierVersionIsKeptAsItsOldestPart() {
        File(directory, "diagnostics.1.log").writeText("older\n")
        File(directory, "diagnostics.log").writeText("newer\n")
        val log = FileDiagnosticLog(directory, clock)

        log.log(Area.APP, "newest")

        log.recent(3).map { it.substringAfterLast(' ') } shouldContainExactly listOf("older", "newer", "newest")
    }

    @Test
    fun theDefaultLimitIsOneMegabyte() {
        FileDiagnosticLog.DEFAULT_MAX_BYTES shouldBe 1_048_576
        val log = FileDiagnosticLog(directory, clock)
        val entry = "x".repeat(1_000)

        repeat(1_500) { log.log(Area.WORK, entry) }
        log.flush()

        stored() shouldBeLessThanOrEqual 1_048_576
        stored() shouldBeGreaterThan 900_000
    }

    private fun stored() = directory.listFiles()!!.sumOf { it.length() }

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
