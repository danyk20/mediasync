/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.transport.rendezvous.RendezvousException
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the last check of this phone's wake-ups found, at [checkedAtMillis]; [problem]: why they don't work. */
data class WakeUpStatus(val checkedAtMillis: Long, val problem: String? = null) {
    val working: Boolean get() = problem == null
}

/**
 * Checks that sending phones can wake this receiving phone over the internet (docs/PLAN.md §10.4): its Worker sends
 * it a check the way a sending phone's wake-up goes, through FCM or the developer's wake relay. It runs when internet
 * sync is set up, and once a day while this phone listens over the internet, so a way that broke shows on this phone,
 * rather than as a sending phone that waits for it in vain.
 */
@Singleton
class WakeUpCheck internal constructor(
    private val preferences: SharedPreferences,
    private val peers: PeerStore,
    private val internet: InternetLinks,
    private val pushToken: () -> String?,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** While the Worker doesn't know the pair or this phone's token yet: the receiver is registering them. */
    private val retryMillis: Long = RETRY_MILLIS,
    /** After listening starts, before a due check: registering with the Worker comes first. */
    private val settleMillis: Long = SETTLE_MILLIS,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        peers: PeerStore,
        network: Connectivity,
        tokens: PushTokens,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        context.getSharedPreferences("wake_check", Context.MODE_PRIVATE),
        peers,
        network.internet,
        tokens::current,
        clock,
        scope,
    )

    private val _status = MutableStateFlow(stored())
    private val due = AtomicBoolean(false)

    /** The last check's result; `null` before the first that could tell. */
    val status: StateFlow<WakeUpStatus?> = _status.asStateFlow()

    /**
     * Checks now, through the Worker at [worker]; `null` if it can't tell yet: no sending phone is paired, the Worker
     * doesn't know them yet, or it can't be reached. Blocks up to a few seconds.
     */
    suspend fun check(worker: String): WakeUpStatus? = withContext(Dispatchers.IO) {
        val producer = peers.producers().firstOrNull { it.pairSecret != null } ?: return@withContext null
        if (pushToken() == null) return@withContext record(NO_FCM)
        repeat(ATTEMPTS) { attempt ->
            try {
                internet.wakeCheck(worker, producer.id)
                return@withContext record(null)
            } catch (e: RendezvousException) {
                when (e.code) {
                    in NOT_REGISTERED_YET -> if (attempt < ATTEMPTS - 1) delay(retryMillis)
                    in TRY_LATER -> return@withContext null
                    else -> return@withContext record(e.message)
                }
            } catch (e: IOException) {
                Diagnostics.log(Area.RECEIVE, "Internet: couldn't check wake-ups now", e)
                return@withContext null
            }
        }
        null
    }

    /**
     * Listening over the internet through [worker] (started, or again): checks, a moment later, if the last check is a
     * day old, or failed an hour ago.
     */
    fun checkIfDue(worker: String) {
        val last = _status.value
        val interval = if (last?.working == false) RECHECK_MILLIS else DAY_MILLIS
        if (last != null && clock.millis() - last.checkedAtMillis < interval) return
        if (!due.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(settleMillis)
                check(worker)
            } finally {
                due.set(false)
            }
        }
    }

    /** A check arrived from FCM: the way works up to this phone. */
    fun arrived() {
        Diagnostics.log(Area.RECEIVE, "Internet: wake-up check arrived")
    }

    /** Internet sync is set up anew: what was checked doesn't apply to the new Worker. */
    fun forget() {
        preferences.edit { clear() }
        _status.value = null
    }

    private fun record(problem: String?): WakeUpStatus {
        val status = WakeUpStatus(clock.millis(), problem)
        preferences.edit {
            putLong(CHECKED_AT, status.checkedAtMillis)
            if (problem == null) remove(PROBLEM) else putString(PROBLEM, problem)
        }
        _status.value = status
        Diagnostics.log(
            Area.RECEIVE,
            if (problem == null) "Internet: wake-ups work" else "Internet: wake-ups don't reach this phone: $problem",
        )
        return status
    }

    private fun stored(): WakeUpStatus? = preferences.getLong(CHECKED_AT, 0).takeIf { it > 0 }?.let {
        WakeUpStatus(it, preferences.getString(PROBLEM, null))
    }

    private companion object {
        const val CHECKED_AT = "checked_at"
        const val PROBLEM = "problem"
        const val ATTEMPTS = 6
        const val RETRY_MILLIS = 2_000L
        const val SETTLE_MILLIS = 20_000L
        val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)
        val RECHECK_MILLIS = TimeUnit.HOURS.toMillis(1)
        val NOT_REGISTERED_YET = setOf("pair_not_found", "no_push_token")
        val TRY_LATER = setOf("wake_throttled", "rate_limited")
        const val NO_FCM = "this phone couldn't register with Firebase Cloud Messaging"
    }
}
