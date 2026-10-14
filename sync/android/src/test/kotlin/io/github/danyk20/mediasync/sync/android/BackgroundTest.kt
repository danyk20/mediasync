/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager
    private val database =
        Room.inMemoryDatabaseBuilder(context, MediaSyncDatabase::class.java).allowMainThreadQueries().build()
    private val peers = PeerStore(database.peerDao())
    private val settings = FakeSettingsRepository()
    private val state = ScheduleState(context)
    private val consumer = deviceId(2)

    /** 20:00 in Zurich; tests move it. */
    private var now = ZonedDateTime.parse("2026-10-14T20:00+02:00[Europe/Zurich]")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = now.zone

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now.toInstant()
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() = database.close()

    private fun kotlinx.coroutines.test.TestScope.scheduler() =
        SyncScheduler(workManager, settings, peers, state, clock, backgroundScope)

    private suspend fun SyncScheduler.applyCurrentPlan() = apply(plans().first())

    private fun pending(role: DeviceRole) = workManager.getWorkInfosForUniqueWork(SyncScheduler.nameOf(role)).get()
        .filter { it.state == WorkInfo.State.ENQUEUED }

    private fun pairWithConsumer(schedule: SyncSchedule? = null) {
        peers.register(PairedPeer(consumer, "Pixel 2 XL", DeviceRole.CONSUMER), "192.168.1.20:47420")
        peers.recordSchedule(consumer, schedule)
    }

    private fun millis(text: String) = ZonedDateTime.parse("$text[Europe/Zurich]").toInstant().toEpochMilli()

    @Test
    fun aReceivingPhoneOpensItsWindowFiveMinutesBeforeTheDueTimeWhileCharging() = runTest {
        settings.setRole(DeviceRole.CONSUMER)

        scheduler().applyCurrentPlan()

        val window = pending(DeviceRole.CONSUMER).single()
        window.initialDelayMillis shouldBe Duration.ofHours(7).minusMinutes(5).toMillis() // 20:00 → 02:55
        window.constraints.requiresCharging() shouldBe true
        window.constraints.requiresStorageNotLow() shouldBe true
        pending(DeviceRole.PRODUCER).shouldBeEmpty()
    }

    @Test
    fun aSendingPhoneFollowsItsReceivingPhonesScheduleWithAStaggeredStart() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.EveryHours(6, LocalTime.of(3, 0)))

        scheduler().applyCurrentPlan()

        val sync = pending(DeviceRole.PRODUCER).single()
        // Due at 21:00, an hour from now, plus up to three minutes so phones don't all connect at once.
        sync.initialDelayMillis shouldBeInRange
            Duration.ofHours(1).toMillis()..Duration.ofHours(1).plusMinutes(3).toMillis()
        sync.constraints.requiresCharging() shouldBe true
    }

    @Test
    fun aSendingPhoneFollowsTheReceivingPhoneItPairedWithLast() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        peers.register(PairedPeer(deviceId(9), "Old Pixel", DeviceRole.CONSUMER), "192.168.1.9:47420")
        Thread.sleep(2) // Paired a moment later.
        pairWithConsumer(SyncSchedule.WhileCharging)

        scheduler().plans().first() shouldBe BackgroundPlan(DeviceRole.PRODUCER, SyncSchedule.WhileCharging)
    }

    @Test
    fun aSendingPhoneOfAnOlderReceiverSyncsDailyAtThree() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(schedule = null)

        scheduler().plans().first() shouldBe BackgroundPlan(DeviceRole.PRODUCER, SyncSchedule.Default)
    }

    @Test
    fun aDueTimeMissedWhileUnpluggedRunsAtTheNextChargeEvenAfterTheAppIsOpened() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        state.markHandled(DeviceRole.PRODUCER, millis("2026-10-13T03:00+02:00")) // Today's 03:00 didn't run.
        val scheduler = scheduler()

        scheduler.applyCurrentPlan()
        scheduler.applyCurrentPlan() // Opening the app reschedules: it still runs at once.

        pending(DeviceRole.PRODUCER).single().initialDelayMillis shouldBe 0
    }

    @Test
    fun manualRunsNothingInTheBackground() = runTest {
        settings.setRole(DeviceRole.CONSUMER)
        settings.setSyncSchedule(SyncSchedule.Manual)

        scheduler().applyCurrentPlan()

        pending(DeviceRole.CONSUMER).shouldBeEmpty()
    }

    @Test
    fun wheneverChargingSyncsWhenNewPhotosArrive() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.WhileCharging)

        scheduler().applyCurrentPlan()

        val sync = pending(DeviceRole.PRODUCER).single()
        sync.initialDelayMillis shouldBe 0
        sync.constraints.requiresCharging() shouldBe true
        sync.constraints.contentUriTriggers shouldHaveSize 2
    }

    @Test
    fun anUnpairedSendingPhoneRunsNothingAndARoleChangeCancelsTheOldRolesWork() = runTest {
        settings.setRole(DeviceRole.CONSUMER)
        val scheduler = scheduler()
        scheduler.applyCurrentPlan()

        settings.setRole(DeviceRole.PRODUCER)
        scheduler.plans().first().shouldBeNull()
        scheduler.applyCurrentPlan()

        pending(DeviceRole.CONSUMER).shouldBeEmpty()
        pending(DeviceRole.PRODUCER).shouldBeEmpty()
    }

    @Test
    fun aRunServesTheDueTimeItBelongsTo() = runTest {
        val scheduler = scheduler()

        now = ZonedDateTime.parse("2026-10-15T02:57+02:00[Europe/Zurich]") // A window opens a little early.
        scheduler.servedDue(DeviceRole.CONSUMER, SyncSchedule.Default)?.toInstant()?.toEpochMilli() shouldBe
            millis("2026-10-15T03:00+02:00")
        now = ZonedDateTime.parse("2026-10-15T03:02+02:00[Europe/Zurich]") // A sync starts a little late.
        scheduler.servedDue(DeviceRole.PRODUCER, SyncSchedule.Default)?.toInstant()?.toEpochMilli() shouldBe
            millis("2026-10-15T03:00+02:00")
        scheduler.servedDue(DeviceRole.PRODUCER, SyncSchedule.WhileCharging).shouldBeNull()
    }

    @Test
    fun aFinishedRunIsRememberedAndQueuesTheNextOne() = runTest {
        settings.setRole(DeviceRole.CONSUMER)
        now = ZonedDateTime.parse("2026-10-15T03:40+02:00[Europe/Zurich]")
        val due = ZonedDateTime.parse("2026-10-15T03:00+02:00[Europe/Zurich]")

        scheduler().finished(DeviceRole.CONSUMER, due)

        state.lastHandledDue(DeviceRole.CONSUMER) shouldBe due.toInstant().toEpochMilli()
        // Next window: 02:55 tomorrow.
        pending(DeviceRole.CONSUMER).single().initialDelayMillis shouldBe
            Duration.ofHours(23).plusMinutes(15).toMillis()
    }

    @Test
    fun theSendingPhoneWaitsForWifiAndRetriesWhileTheReceiverIsAway() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        val sync =
            mockk<ProducerSync> { coEvery { syncAndWait() } returns ProducerState.Failed(SyncFailure.UNREACHABLE) }

        runSyncWorker(sync, wifi = emptyList()) shouldBe ListenableWorker.Result.retry()
        coVerify(exactly = 0) { sync.syncAndWait() }

        runSyncWorker(sync) shouldBe ListenableWorker.Result.retry()
    }

    @Test
    fun aDoneSyncIsRememberedAndTheNextOneQueued() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        now = ZonedDateTime.parse("2026-10-15T03:02+02:00[Europe/Zurich]")
        val sync = mockk<ProducerSync> { coEvery { syncAndWait() } returns ProducerState.Idle() }

        runSyncWorker(sync) shouldBe ListenableWorker.Result.success()

        state.lastHandledDue(DeviceRole.PRODUCER) shouldBe millis("2026-10-15T03:00+02:00")
        pending(DeviceRole.PRODUCER) shouldHaveSize 1
    }

    @Test
    fun anUnpairedSendingPhoneHasNothingToSync() = runTest {
        val sync = mockk<ProducerSync>()

        runSyncWorker(sync) shouldBe ListenableWorker.Result.success()
    }

    @Test
    fun aReceiveWindowClosesOnceEveryPhoneIsDone() {
        val due = ZonedDateTime.parse("2026-10-15T03:00+02:00[Europe/Zurich]")
        val opened = millis("2026-10-15T02:55+02:00")
        val quiet = ReceiverState(lastActivityMillis = millis("2026-10-15T03:10+02:00"))

        receiveWindowDone(due, opened, millis("2026-10-15T04:09+02:00"), quiet) shouldBe false
        receiveWindowDone(due, opened, millis("2026-10-15T04:10+02:00"), quiet) shouldBe true
        // Not while something is still arriving, but after four hours in any case.
        val busy = quiet.copy(transfers = mapOf(deviceId(1) to mockk()))
        receiveWindowDone(due, opened, millis("2026-10-15T04:30+02:00"), busy) shouldBe false
        receiveWindowDone(due, opened, millis("2026-10-15T06:55+02:00"), busy) shouldBe true
    }

    @Test
    fun aLateWindowStaysOpenForAnHourAndWheneverChargingUntilUnplugged() {
        val due = ZonedDateTime.parse("2026-10-15T03:00+02:00[Europe/Zurich]")
        val pluggedInAt = millis("2026-10-15T07:00+02:00") // Caught up at the next charge.

        receiveWindowDone(due, pluggedInAt, millis("2026-10-15T07:50+02:00"), ReceiverState()) shouldBe false
        receiveWindowDone(due, pluggedInAt, millis("2026-10-15T08:00+02:00"), ReceiverState()) shouldBe true
        receiveWindowDone(null, pluggedInAt, millis("2026-10-16T07:00+02:00"), ReceiverState()) shouldBe false
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runSyncWorker(
        sync: ProducerSync,
        wifi: List<InetAddress> = listOf(InetAddress.getByName("192.168.1.30")),
    ): ListenableWorker.Result {
        val scheduler = scheduler()
        val notifications = mockk<SyncNotifications>(relaxed = true)
        val worker = TestListenableWorkerBuilder<ScheduledSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = ScheduledSyncWorker(appContext, workerParameters, sync, peers, {
                        wifi
                    }, scheduler, notifications)
                },
            )
            .build()
        return worker.doWork()
    }
}
