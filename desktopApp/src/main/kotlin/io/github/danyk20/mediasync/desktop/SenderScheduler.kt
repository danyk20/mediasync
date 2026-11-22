/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.model.nextDue
import io.github.danyk20.mediasync.core.model.previousDue
import io.github.danyk20.mediasync.sync.android.DeviceConditions
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How often the scheduler looks at the clock, and how long it waits before it tries again; tests shorten them. */
data class SenderTimings(
    /** The longest sleep between two looks at the clock: a laptop waking up is noticed within this time. */
    val tickMillis: Long = 60_000,
    /** *Whenever charging*: how long the folders stay quiet before the new files are sent. */
    val debounceMillis: Long = 30_000,
    /** After a sync that could not reach the receiver, as on a phone (15 minutes). */
    val retryMillis: Long = 900_000,
)

/**
 * Syncs a sending computer on the household schedule its receiver told it, as a phone does (docs/PLAN.md §10.1):
 * a timed schedule syncs at each due time while plugged in, with one catch-up when a due time was missed (asleep,
 * off, or on battery); *Whenever charging* syncs once at start and again after the folders settle while plugged in;
 * *Manual*, and a computer not paired with a receiver, never syncs by itself. Only one sync runs at a time, and a
 * sync already running counts as the one a due time asks for.
 *
 * A new schedule never stops a sync in progress: it applies once that sync has ended. The due time handled last is
 * kept durably ([saveHandledMillis], read by [lastHandledMillis]), so a restart or a schedule change neither repeats
 * nor skips a catch-up. Time and waiting are injected, so tests run without them.
 */
@Suppress("LongParameterList") // Collaborators, one each.
class SenderScheduler(
    private val consumerSchedule: Flow<SyncSchedule?>,
    private val folderChanges: Flow<Unit>,
    private val lastSyncMillis: () -> Long?,
    /** The due time handled last, as kept by [saveHandledMillis]; `null` before any schedule was followed. */
    private val lastHandledMillis: () -> Long?,
    private val saveHandledMillis: (Long) -> Unit,
    private val conditions: DeviceConditions,
    private val sync: suspend (pauseWhen: () -> Boolean) -> ProducerState,
    private val clock: Clock = Clock.systemUTC(),
    /** Read at each look at the clock, so a change of time zone is followed while the scheduler runs. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
    private val timings: SenderTimings = SenderTimings(),
) {
    private val _nextSync = MutableStateFlow<ZonedDateTime?>(null)
    private val _active = MutableStateFlow(false)

    /** The next due time of a timed schedule while the scheduler runs; `null` otherwise. */
    val nextSync: StateFlow<ZonedDateTime?> = _nextSync.asStateFlow()

    /** Whether [run] is running. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * Follows the schedule until cancelled; cancelling stops a sync in progress after its current file. A failure in
     * one step is logged and tried again after a tick: the scheduler does not die silently.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails is logged, and the scheduler goes on.
    suspend fun run() {
        _active.value = true
        try {
            coroutineScope {
                val latest = MutableStateFlow<Wanted?>(null)
                launch { consumerSchedule.distinctUntilChanged().collect { latest.value = Wanted(it) } }
                while (true) {
                    val wanted = latest.filterNotNull().first()
                    try {
                        follow(wanted.schedule) { latest.value !== wanted }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Sending: the scheduler hit a problem, tries again in a minute: $e")
                        pause(timings.tickMillis)
                    }
                }
            }
        } finally {
            _active.value = false
            _nextSync.value = null
        }
    }

    /** As Android's `SyncScheduler.missedSync`: latest due > 1 h old and later than every sync; else `null`. */
    fun missedSync(schedule: SyncSchedule): ZonedDateTime? {
        val now = now()
        val previous = schedule.previousDue(now) ?: return null
        val handled = maxOf(lastHandledMillis() ?: return null, lastSyncMillis() ?: Long.MIN_VALUE)
        return previous.takeIf { it.millis() > handled && Duration.between(it, now) > MISSED_AFTER }
    }

    /** Follows [schedule] until [isStale] says a newer one came in; that one is applied by the caller. */
    private suspend fun follow(schedule: SyncSchedule?, isStale: () -> Boolean) {
        _nextSync.value = null
        when (schedule) {
            null -> {
                log("Sending: not paired, nothing is sent by itself")
                waitWhile(isStale)
            }

            SyncSchedule.Manual -> {
                log("Sending: manual schedule, syncs run when you press Sync now")
                waitWhile(isStale)
            }

            SyncSchedule.WhileCharging -> followChanges(isStale)

            is SyncSchedule.Timed -> followDueTimes(schedule, isStale)
        }
    }

    /** Until the schedule changes, one tick at a time: nothing runs meanwhile. */
    private suspend fun waitWhile(isStale: () -> Boolean) {
        while (!isStale()) pause(timings.tickMillis)
    }

    private suspend fun followDueTimes(schedule: SyncSchedule.Timed, isStale: () -> Boolean) {
        // First look at a schedule with nothing handled yet: the current period counts as done (the last sync, if any).
        if (lastHandledMillis() == null) {
            (lastSyncMillis() ?: schedule.previousDue(now())?.millis())?.let(saveHandledMillis)
        }
        while (!isStale()) {
            val now = now()
            val previous = schedule.previousDue(now)
            val next = schedule.nextDue(now)
            _nextSync.value = next
            if (previous != null && previous.millis() > handledMillis() && conditions.pluggedIn()) {
                val retryAfter = attempt()
                if (retryAfter == null) saveHandledMillis(previous.millis()) else pause(retryAfter)
            } else {
                val untilNext = next?.let { Duration.between(now, it).toMillis() } ?: timings.tickMillis
                pause(minOf(timings.tickMillis, untilNext).coerceAtLeast(1))
            }
        }
    }

    private suspend fun followChanges(isStale: () -> Boolean) {
        coroutineScope {
            val lastChange = AtomicLong(0)
            val dirty = AtomicBoolean(true) // At start: send what is there.
            // UNDISPATCHED: listening before the first sync starts, so a file added meanwhile is not missed.
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                folderChanges.collect {
                    lastChange.set(clock.millis())
                    dirty.set(true)
                }
            }
            try {
                while (!isStale()) {
                    val quiet = clock.millis() - lastChange.get()
                    when {
                        !dirty.get() -> pause(timings.tickMillis)

                        quiet < timings.debounceMillis -> pause(timings.debounceMillis - quiet)

                        !conditions.pluggedIn() -> pause(timings.tickMillis)

                        else -> {
                            dirty.set(false)
                            attempt()?.let { retryAfter ->
                                dirty.set(true)
                                pause(retryAfter)
                            }
                        }
                    }
                }
            } finally {
                listener.cancel()
            }
        }
    }

    /**
     * Runs one sync; returns how long to wait before trying again, or `null` when the due time (or the change) is
     * handled. A sync stopped because the power went off is tried again once plugged in; one the user cancelled while
     * plugged in, or one that failed for good, is not: it waits for the next due time.
     */
    private suspend fun attempt(): Long? {
        log("Sending: scheduled sync starts")
        // Set when the sync asks to stop for power; read here, not from the power now (a re-plug may have restored it).
        var stoppedForPower = false
        val end = sync {
            val lost = !conditions.pluggedIn()
            if (lost) stoppedForPower = true
            lost
        }
        val retryAfter = when {
            end is ProducerState.Failed && end.reason in RETRIED -> timings.retryMillis

            // Nothing to retry until the user acts, e.g. pairs again.
            end is ProducerState.Failed -> null

            // Stopped by unplugging: again once it can.
            end is ProducerState.Idle && end.lastResult?.cancelled == true && stoppedForPower -> timings.tickMillis

            else -> null // Done, or cancelled by the user.
        }
        log("Sending: scheduled sync ended ${if (retryAfter == null) "done" else "unfinished, tries again"}")
        return retryAfter
    }

    /** The later of the due time handled last and the last sync; a due time at or before it is done. */
    private fun handledMillis(): Long = maxOf(lastHandledMillis() ?: Long.MIN_VALUE, lastSyncMillis() ?: Long.MIN_VALUE)

    /** The clock's instant in the zone of the moment, so a change of time zone counts from the next look. */
    private fun now(): ZonedDateTime = clock.instant().atZone(zone())

    private fun ZonedDateTime.millis() = toInstant().toEpochMilli()

    /** A schedule as the consumer told it; a new one is a new object, so the loops can tell it is newer. */
    private class Wanted(val schedule: SyncSchedule?)

    private companion object {
        val MISSED_AFTER: Duration = Duration.ofHours(1)
        val RETRIED = setOf(SyncFailure.UNREACHABLE, SyncFailure.CONNECTION_LOST)
    }
}
