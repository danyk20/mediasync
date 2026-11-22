/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.createGraphFactory
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.DeviceConditions
import io.github.danyk20.mediasync.sync.android.PauseReason
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A clock the test moves by hand; always UTC, so the due times below are the same on every machine. */
private class FakeClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** Power that can be cut, and whose check can fail the given number of times first. */
private class FakePower(var plugged: Boolean = true) : DeviceConditions {
    var failingChecks = 0

    override fun pauseReason(): PauseReason? = null

    override fun pluggedIn(): Boolean {
        if (failingChecks > 0) {
            failingChecks--
            error("the power query failed")
        }
        return plugged
    }
}

/**
 * A computer with a [SenderScheduler] and fakes only: the clock, the folder changes, the power, the sync, the time
 * zone and the handled due time, which is kept here as the file keeps it. Time moves when the scheduler waits, and
 * each wait also runs the actions the test planned at that time. [runFor] stops the scheduler when the planned time
 * is over.
 */
@Suppress("LongParameterList") // A test computer: the schedule, what was sent before, and the fakes.
private class Harness(
    schedule: SyncSchedule?,
    lastSync: Instant? = null,
    handled: Instant? = null,
    start: Instant = START,
    private val outcomes: MutableList<ProducerState> = mutableListOf(),
) {
    private val origin = start
    val clock = FakeClock(start)
    val power = FakePower()
    var zone: ZoneId = ZoneOffset.UTC
    val schedules = MutableStateFlow(schedule)
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val syncs = mutableListOf<Instant>()
    var completedSyncs = 0
    var running = 0
    var mostAtOnce = 0
    var duringSync: suspend () -> Unit = {}
    private var pauseCheck: () -> Boolean = { false }
    private var stored: Long? = handled?.toEpochMilli()
    private val actions = mutableListOf<Pair<Instant, () -> Unit>>()
    private var limit: Instant = Instant.MAX
    private val parked = CompletableDeferred<Unit>()
    private lateinit var job: Job

    /** The handled due time as the file holds it after the run; pass it to a restarted computer. */
    val handledMarker: Instant? get() = stored?.let { Instant.ofEpochMilli(it) }

    val scheduler = SenderScheduler(
        consumerSchedule = schedules,
        folderChanges = changes,
        lastSyncMillis = { lastSync?.toEpochMilli() },
        lastHandledMillis = { stored },
        saveHandledMillis = { stored = it },
        conditions = power,
        sync = { pauseWhen ->
            pauseCheck = pauseWhen
            syncs += clock.now
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            try {
                duringSync()
                completedSyncs++
            } finally {
                running--
            }
            outcomes.removeFirstOrNull() ?: ProducerState.Idle(SyncResult())
        },
        clock = clock,
        zone = { zone },
        pause = { millis ->
            val target = clock.now.plusMillis(millis)
            if (target > limit) {
                parked.complete(Unit)
                awaitCancellation()
            }
            clock.now = target
            fire(target)
            yield()
        },
    )

    /** Asks the running sync's `pauseWhen`, as the sync asks between its files: `true` means it stops for power. */
    fun askPauseWhen(): Boolean = pauseCheck()

    /** Plans [action] at [after] from the start. */
    fun at(after: Duration, action: () -> Unit) {
        actions += origin.plusMillis(after.inWholeMilliseconds) to action
    }

    /** Stops the scheduler, as the app does when it quits. */
    fun stop() = job.cancel()

    /** Runs the scheduler until [duration] after the start, then stops it. */
    fun runFor(duration: Duration) = runBlocking<Unit> {
        limit = origin.plusMillis(duration.inWholeMilliseconds)
        val run = launch { scheduler.run() }
        job = run
        val stopper = launch {
            parked.await()
            run.cancel()
        }
        run.join()
        stopper.cancel()
    }

    /** When each sync started, as time since the start. */
    fun syncedAfter(): List<Duration> = syncs.map { java.time.Duration.between(origin, it).toMillis().milliseconds }

    private fun fire(until: Instant) {
        val due = actions.filter { it.first <= until }
        actions.removeAll(due)
        due.sortedBy { it.first }.forEach { it.second() }
    }

    companion object {
        /** A Tuesday, 10:00 UTC: the next daily 03:00 is 17 hours away. */
        val START: Instant = Instant.parse("2026-03-10T10:00:00Z")
    }
}

/** Syncs by the household schedule, on fakes: [SenderScheduler] as the desktop app runs it. */
class SenderSchedulerTest {

    @TempDir
    lateinit var directory: Path

    private val daily = SyncSchedule.Daily(LocalTime(3, 0))
    private val lastMarch8 = Instant.parse("2026-03-08T04:00:00Z")

    @Test
    fun `a timed schedule syncs at its due time`() {
        val h = Harness(daily)

        h.runFor(20.hours)

        h.syncedAfter() shouldBe listOf(17.hours)
    }

    @Test
    fun `the first schedule on a computer counts the current period as done`() {
        val h = Harness(daily)

        h.runFor(1.hours)

        h.syncedAfter() shouldBe emptyList()
    }

    @Test
    fun `two missed due times asleep make one catch-up sync`() {
        val h = Harness(daily, lastSync = lastMarch8)

        h.runFor(2.hours)

        h.syncedAfter() shouldBe listOf(0.hours)
    }

    @Test
    fun `a sync after the due time counts as the due time's sync`() {
        val h = Harness(daily, lastSync = Instant.parse("2026-03-10T03:30:00Z"))

        h.runFor(5.hours)

        h.syncedAfter() shouldBe emptyList()
    }

    @Test
    fun `on battery the timed sync waits for the charger`() {
        val h = Harness(daily, lastSync = lastMarch8)
        h.power.plugged = false
        h.at(2.hours) { h.power.plugged = true }

        h.runFor(3.hours)

        h.syncedAfter() shouldBe listOf(2.hours)
    }

    @Test
    fun `a sync that cannot reach the receiver is tried again after 15 minutes`() {
        val h = Harness(
            daily,
            lastSync = lastMarch8,
            outcomes = mutableListOf(ProducerState.Failed(SyncFailure.UNREACHABLE)),
        )

        h.runFor(1.hours)

        h.syncedAfter() shouldBe listOf(0.hours, 15.minutes)
    }

    @Test
    fun `a sync that is not paired is not tried again by itself`() {
        val h = Harness(
            daily,
            lastSync = lastMarch8,
            outcomes = mutableListOf(ProducerState.Failed(SyncFailure.NOT_PAIRED)),
        )

        h.runFor(2.hours)

        h.syncedAfter() shouldBe listOf(0.hours)
    }

    @Test
    fun `a sync stopped by unplugging is tried again once plugged in`() {
        val h = Harness(
            daily,
            lastSync = lastMarch8,
            outcomes = mutableListOf(ProducerState.Idle(SyncResult(cancelled = true))),
        )
        var first = true
        h.duringSync = {
            if (first) {
                first = false
                h.power.plugged = false
                h.askPauseWhen()
            }
        }
        h.at(1.hours) { h.power.plugged = true }

        h.runFor(2.hours)

        h.syncedAfter() shouldBe listOf(0.hours, 1.hours)
    }

    @Test
    fun `a sync stopped for power is tried again when power is back before its result is read`() {
        val h = Harness(
            daily,
            lastSync = lastMarch8,
            outcomes = mutableListOf(ProducerState.Idle(SyncResult(cancelled = true))),
        )
        var first = true
        h.duringSync = {
            if (first) {
                first = false
                h.power.plugged = false
                h.askPauseWhen() // The sync stops for power here...
                h.power.plugged = true // ...and a quick re-plug restores power before the result is read.
            }
        }

        h.runFor(2.hours)

        h.syncedAfter() shouldBe listOf(0.hours, 1.minutes)
    }

    @Test
    fun `a sync the user cancels while plugged in is not restarted before the next due time`() {
        val h = Harness(
            daily,
            lastSync = lastMarch8,
            outcomes = mutableListOf(ProducerState.Idle(SyncResult(cancelled = true))),
        )

        h.runFor(20.hours)

        h.syncedAfter() shouldBe listOf(0.hours, 17.hours)
    }

    @Test
    fun `the home screen sees the missed sync and no next sync once the scheduler stops`() {
        val h = Harness(daily, lastSync = lastMarch8)
        h.power.plugged = false

        h.runFor(5.minutes)

        h.scheduler.nextSync.value.shouldBeNull()
        h.scheduler.missedSync(daily)?.toInstant() shouldBe Instant.parse("2026-03-10T03:00:00Z")
        h.scheduler.missedSync(SyncSchedule.Manual).shouldBeNull()
    }

    @Test
    fun `a manual schedule never syncs by itself, even when the folders change`() {
        val h = Harness(SyncSchedule.Manual)
        h.at(1.hours) { h.changes.tryEmit(Unit) }

        h.runFor(3.days)

        h.syncedAfter() shouldBe emptyList()
    }

    @Test
    fun `a computer that is not paired never syncs by itself`() {
        val h = Harness(null, lastSync = lastMarch8)

        h.runFor(3.days)

        h.syncedAfter() shouldBe emptyList()
    }

    @Test
    fun `while charging, a sync starts at once and again after the folders settle`() {
        val h = Harness(SyncSchedule.WhileCharging)
        h.at(10.minutes) { h.changes.tryEmit(Unit) }

        h.runFor(20.minutes)

        h.syncedAfter() shouldBe listOf(0.minutes, 10.minutes + 30.seconds)
    }

    @Test
    fun `a burst of folder changes makes one sync, after the last one settles`() {
        val h = Harness(SyncSchedule.WhileCharging)
        h.at(10.minutes) { h.changes.tryEmit(Unit) }
        h.at(10.minutes + 30.seconds) { h.changes.tryEmit(Unit) }
        h.at(11.minutes) { h.changes.tryEmit(Unit) }

        h.runFor(20.minutes)

        h.syncedAfter() shouldBe listOf(0.minutes, 11.minutes + 30.seconds)
    }

    @Test
    fun `while charging, a sync waits until the computer is plugged in`() {
        val h = Harness(SyncSchedule.WhileCharging)
        h.power.plugged = false
        h.at(10.minutes) { h.power.plugged = true }

        h.runFor(20.minutes)

        h.syncedAfter() shouldBe listOf(10.minutes)
    }

    @Test
    fun `changes during a sync start one more sync, never two at once`() {
        val h = Harness(SyncSchedule.WhileCharging)
        var first = true
        h.duringSync = {
            if (first) {
                first = false
                h.changes.tryEmit(Unit)
                h.changes.tryEmit(Unit)
                h.clock.now = h.clock.now.plusSeconds(300)
                yield()
            }
        }

        h.runFor(30.minutes)

        h.syncs.size shouldBe 2
        h.mostAtOnce shouldBe 1
    }

    @Test
    fun `a new schedule does not stop a sync in progress, and applies once it ends`() {
        val h = Harness(SyncSchedule.WhileCharging)
        var first = true
        h.duringSync = {
            if (first) {
                first = false
                h.schedules.value = daily
                yield()
                yield()
            }
        }

        h.runFor(20.hours)

        h.completedSyncs shouldBe 2
        h.syncedAfter() shouldBe listOf(0.hours, 17.hours)
    }

    @Test
    fun `a restart after a user cancel does not repeat the catch-up`() {
        val before = Harness(
            daily,
            lastSync = lastMarch8,
            outcomes = mutableListOf(ProducerState.Idle(SyncResult(cancelled = true))),
        )
        before.runFor(1.hours)
        before.syncedAfter() shouldBe listOf(0.hours)

        val after =
            Harness(
                daily,
                lastSync = lastMarch8,
                handled = before.handledMarker,
                start = Harness.START.plusSeconds(3600),
            )
        after.runFor(1.hours)

        after.syncedAfter() shouldBe emptyList()
    }

    @Test
    fun `a restart after a missed due time with no sync ever makes exactly one catch-up`() {
        val before = Harness(daily)
        before.runFor(1.hours)
        before.syncedAfter() shouldBe emptyList()

        // Next morning at 05:00: the 03:00 due time was missed by two hours.
        val after = Harness(daily, handled = before.handledMarker, start = Harness.START.plusSeconds(19 * 3600))
        after.runFor(20.hours)

        after.syncedAfter() shouldBe listOf(0.hours)
    }

    @Test
    fun `the handled due time survives a restart and is cleared by reset`() {
        val paths = AppPaths(directory, isolated = true)
        val due = lastMarch8.toEpochMilli()

        DesktopSyncRecorder(paths).apply {
            markHandled(due)
            recordSync(atMillis = due + 1_000, waitingForSpace = 0)
        }
        DesktopSyncRecorder(paths).handledMillis() shouldBe due

        DesktopSyncRecorder(paths).forget()
        DesktopSyncRecorder(paths).handledMillis().shouldBeNull()
    }

    @Test
    fun `the due times follow a change of time zone while the scheduler runs`() {
        val h = Harness(daily)
        h.at(1.hours) { h.zone = ZoneOffset.ofHours(5) }

        h.runFor(20.hours)

        // 03:00 at +05:00 is 22:00 UTC: 12 hours after the start, not 17.
        h.syncedAfter() shouldBe listOf(12.hours)
    }

    @Test
    fun `a power check that fails once is tried again after one tick`() {
        val h = Harness(daily, lastSync = lastMarch8)
        h.power.failingChecks = 1

        h.runFor(1.hours)

        h.syncedAfter() shouldBe listOf(1.minutes)
    }

    @Test
    fun `stopping the scheduler ends it, and no further sync starts`() {
        val h = Harness(daily)
        h.at(20.hours) { h.stop() }

        h.runFor(4.days)

        h.syncedAfter() shouldBe listOf(17.hours)
        h.scheduler.active.value shouldBe false
    }

    @Test
    fun `the receiving role never runs the scheduler, and the sending role does`() = runBlocking<Unit> {
        val receivingPart = FakeRolePart()
        val harness = Harness(daily)
        val schedulerPart = SchedulerPart(harness)

        val receiving = RoleLifecycle(
            flowOf(DeviceRole.CONSUMER),
            mapOf(DeviceRole.CONSUMER to receivingPart, DeviceRole.PRODUCER to schedulerPart),
        )
        receiving.run(this)
        yield()
        harness.scheduler.active.value shouldBe false
        harness.syncs.size shouldBe 0
        receiving.stopAll()

        val sending = RoleLifecycle(
            flowOf(DeviceRole.PRODUCER),
            mapOf(DeviceRole.CONSUMER to receivingPart, DeviceRole.PRODUCER to schedulerPart),
        )
        sending.run(this)
        yield()
        harness.scheduler.active.value shouldBe true
        sending.stopAll()
        harness.scheduler.active.value shouldBe false
    }

    @Test
    fun `a graph starts with the scheduler stopped, and shuts down`() {
        val graph = createGraphFactory<DesktopGraph.Factory>()
            .create(AppPaths(directory.resolve("data"), isolated = true))

        graph.senderScheduler.active.value shouldBe false

        AppShutdown(graph.shutdownSteps()).run()
    }

    /** A role part that does nothing but record its start and stop. */
    private class FakeRolePart : RolePart {
        override fun start(scope: CoroutineScope) = Unit

        override suspend fun stop() = Unit
    }

    /** The sending role's part in this test: it runs the scheduler, as [SendingPart] does once started. */
    private class SchedulerPart(private val harness: Harness) : RolePart {
        private var job: Job? = null

        override fun start(scope: CoroutineScope) {
            job = scope.launch { harness.scheduler.run() }
        }

        override suspend fun stop() {
            job?.cancelAndJoin()
            job = null
        }
    }
}
