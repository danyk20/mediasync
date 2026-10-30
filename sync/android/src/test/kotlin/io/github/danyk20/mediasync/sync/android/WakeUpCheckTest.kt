/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.ConsumerFacts
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.rendezvous.RendezvousException
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.Closeable
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WakeUpCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, MediaSyncDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val peers = PeerStore(database.peerDao())
    private val producer = deviceId(3)
    private var now = Instant.parse("2026-10-30T14:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?) = this

        override fun instant() = now
    }
    private var pushToken: String? = "fid-1"

    /** What the Worker answers the next checks, in order; then that wake-ups work. */
    private val answers = ArrayDeque<IOException>()
    private val checked = CopyOnWriteArrayList<Pair<String, DeviceId>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val internet = object : InternetLinks {
        override fun allowed() = true

        override fun connect(consumer: DeviceId, worker: String, pairSecret: ByteString) = error("Not sending")

        override fun listen(worker: String, pairs: Map<DeviceId, ByteString>, serve: (PeerConnection) -> Unit) =
            Closeable {}

        override fun relayUsage(worker: String, producer: DeviceId) = error("Not used")

        override fun wakeCheck(worker: String, producer: DeviceId) {
            checked += worker to producer
            synchronized(answers) { answers.removeFirstOrNull() }?.let { throw it }
        }
    }

    private fun wakeUpCheck() = WakeUpCheck(
        context.getSharedPreferences("wake_check", Context.MODE_PRIVATE),
        peers,
        internet,
        { pushToken },
        clock,
        scope,
        retryMillis = 0,
        settleMillis = 0,
    )

    @After
    fun tearDown() {
        scope.cancel()
        context.getSharedPreferences("wake_check", Context.MODE_PRIVATE).edit().clear().commit()
        database.close()
    }

    private fun pairProducer() {
        peers.register(PairedPeer(producer, "Anna's Galaxy", DeviceRole.PRODUCER), null)
        peers.recordConsumer(producer, ConsumerFacts(pairSecret = "secret".encodeUtf8().sha256()))
    }

    @Test
    fun wakeUpsThatGetThroughAreRememberedAcrossRestarts() = runTest {
        pairProducer()

        val status = wakeUpCheck().check(WORKER)

        status shouldBe WakeUpStatus(now.toEpochMilli())
        checked shouldBe listOf(WORKER to producer)
        wakeUpCheck().status.value shouldBe status
    }

    @Test
    fun aWorkerThatCantReachTheWakeRelaySaysWhy() = runTest {
        pairProducer()
        answers += RendezvousException(
            "wake_relay_failed",
            502,
            "The wake relay answered HTTP 404 (Cloudflare error 1042)",
        )

        val status = wakeUpCheck().check(WORKER)

        status?.working shouldBe false
        status?.problem shouldBe "wake_relay_failed: The wake relay answered HTTP 404 (Cloudflare error 1042)"
    }

    @Test
    fun whileTheWorkerDoesntKnowThePairOrTokenYetItAsksAgain() = runTest {
        pairProducer()
        answers += RendezvousException("pair_not_found", 404, "No such pair")
        answers += RendezvousException("no_push_token", 409, "The consumer hasn't registered for wake-ups")

        wakeUpCheck().check(WORKER)?.working shouldBe true

        checked.size shouldBe 3
    }

    @Test
    fun whatCantBeToldNowChangesNothing() = runTest {
        val check = wakeUpCheck()
        check.check(WORKER).shouldBeNull() // No sending phone is paired.
        pairProducer()
        answers += IOException("No network")
        check.check(WORKER).shouldBeNull()
        answers += RendezvousException("wake_throttled", 429, "A wake-up check was sent moments ago")
        check.check(WORKER).shouldBeNull()

        check.status.value.shouldBeNull()
    }

    @Test
    fun aPhoneThatCouldntRegisterWithFcmCantBeWoken() = runTest {
        pairProducer()
        pushToken = null

        wakeUpCheck().check(WORKER)?.problem shouldBe "this phone couldn't register with Firebase Cloud Messaging"
        checked shouldBe emptyList()
    }

    @Test
    fun whileListeningItChecksOnceADayOrAnHourAfterAFailure() {
        pairProducer()
        val check = wakeUpCheck()

        check.checkIfDue(WORKER)
        awaitChecks(1)
        check.checkIfDue(WORKER) // The same day: not again.
        now = now.plusSeconds(TimeUnit.HOURS.toSeconds(23))
        check.checkIfDue(WORKER)
        Thread.sleep(SETTLE_MILLIS)
        checked.size shouldBe 1

        now = now.plusSeconds(TimeUnit.HOURS.toSeconds(2))
        synchronized(answers) { answers += RendezvousException("push_failed", 502, "FCM didn't accept the wake-up") }
        check.checkIfDue(WORKER)
        awaitChecks(2)
        check.status.value?.working shouldBe false
        now = now.plusSeconds(TimeUnit.MINUTES.toSeconds(61)) // It failed: checked again an hour later.
        check.checkIfDue(WORKER)
        awaitChecks(3)
        check.status.value?.working shouldBe true
    }

    /** Due checks run in the background. */
    private fun awaitChecks(count: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (checked.size < count && System.nanoTime() < end) Thread.sleep(10)
        Thread.sleep(SETTLE_MILLIS) // Its result is recorded just after.
        checked.size shouldBe count
    }

    private companion object {
        const val WORKER = "https://mediasync-rendezvous.anna.workers.dev"
        const val SETTLE_MILLIS = 200L
    }
}
