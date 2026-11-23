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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import okio.FileSystem
import okio.Path

class FileDiagnosticLogTest {

    private val directory: Path = tempDirectory()
    private val zone = TimeZone.of("Europe/Zurich")
    private val clock = FixedClock(Instant.parse("2026-10-14T07:30:00.250Z"))

    private fun newLog(maxBytes: Long = FileDiagnosticLog.DEFAULT_MAX_BYTES, folder: Path = directory) =
        FileDiagnosticLog(FileSystem.SYSTEM, folder, clock, zone, maxBytes)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun entriesAreTimeStampedOneLineEachInOrder() {
        val log = newLog()

        log.log(Area.SEND, "first")
        log.log(Area.RECEIVE, "second\nline")

        log.recent(10) shouldContainExactly listOf(
            "2026-10-14 09:30:00.250 +0200 send     first",
            "2026-10-14 09:30:00.250 +0200 receive  second line",
        )
    }

    @Test
    fun theLogRollsOverDroppingTheOldestEntriesFirst() {
        val log = newLog(maxBytes = 10_000)

        repeat(1_000) {
            log.log(Area.WORK, "entry $it")
            log.drain()
            directory.stored() shouldBeLessThanOrEqual 10_000
        }

        val kept = log.recent(Int.MAX_VALUE).map { it.substringAfter("entry ").toInt() }
        kept.last() shouldBe 999
        kept shouldBe (kept.first()..999).toList() // The newest, in order, with nothing missing in between.
        directory.stored() shouldBeGreaterThan 8_000 // At most about a tenth is dropped at once.
        directory.fileCount() shouldBeLessThanOrEqual 11
    }

    @Test
    fun aSmallerLimitDropsTheOldestEntriesAtOnce() {
        val log = newLog(maxBytes = 100_000)
        repeat(1_000) { log.log(Area.WORK, "entry $it") }
        log.drain()
        directory.stored() shouldBeGreaterThan 40_000

        log.limitTo(5_000)

        log.read().length shouldBeLessThanOrEqual 5_000
        directory.stored() shouldBeLessThanOrEqual 5_000
        log.recent(1).single() shouldContain "entry 999"
        log.read() shouldNotContain "entry 0\n"

        repeat(500) { log.log(Area.WORK, "more $it") }
        log.drain()
        directory.stored() shouldBeLessThanOrEqual 5_000
        log.recent(1).single() shouldContain "more 499"
    }

    @Test
    fun aLargerLimitKeepsWhatsThere() {
        val log = newLog(maxBytes = 5_000)
        repeat(200) { log.log(Area.WORK, "entry $it") }
        val before = log.read()

        log.limitTo(50_000)
        repeat(200) { log.log(Area.WORK, "more $it") }

        log.read() shouldStartWith before
        directory.stored() shouldBeGreaterThan 10_000
    }

    @Test
    fun anEntryLargerThanTheWholeLogIsntKept() {
        val log = newLog(maxBytes = 50)
        repeat(3) { log.log(Area.WORK, "a line longer than fifty bytes, number $it") }

        log.read() shouldBe ""
        directory.stored() shouldBe 0
    }

    @Test
    fun aLogFromAnEarlierVersionIsKeptAsItsOldestPart() {
        FileSystem.SYSTEM.write(directory / "diagnostics.1.log") { writeUtf8("older\n") }
        FileSystem.SYSTEM.write(directory / "diagnostics.log") { writeUtf8("newer\n") }
        val log = newLog()

        log.log(Area.APP, "newest")

        log.recent(3).map { it.substringAfterLast(' ') } shouldContainExactly listOf("older", "newer", "newest")
    }

    @Test
    fun theDefaultLimitIsOneMegabyte() {
        FileDiagnosticLog.DEFAULT_MAX_BYTES shouldBe 1_048_576
        val log = newLog()
        val entry = "x".repeat(1_000)

        repeat(1_500) { log.log(Area.WORK, entry) }
        log.drain()

        directory.stored() shouldBeLessThanOrEqual 1_048_576
        directory.stored() shouldBeGreaterThan 900_000
    }

    @Test
    fun clearingStartsAfresh() {
        val log = newLog()
        log.log(Area.APP, "before")

        log.clear()
        log.log(Area.APP, "after")

        log.recent(10).single() shouldContain "after"
    }

    @Test
    fun manyCoroutinesCanLogAtOnce() = runTest {
        val log = newLog()

        (1..8).map { t -> async(Dispatchers.Default) { repeat(100) { log.log(Area.APP, "thread $t entry $it") } } }
            .awaitAll()

        log.read().lines().count { it.contains("entry") } shouldBe 800
    }

    @Test
    fun theDirectoryIsCreatedWhenFirstNeeded() {
        val folder = directory / "sub"
        val log = newLog(folder = folder)

        log.log(Area.APP, "first")

        log.recent(1).single() shouldContain "first"
        FileSystem.SYSTEM.exists(folder) shouldBe true
    }
}
