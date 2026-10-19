/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.danyk20.mediasync.core.database.ConsumerFacts
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private val history = SyncHistory(context)
    private var pauseReason: PauseReason? = null
    private var pluggedIn = true
    private var chargingHeld = false
    private val conditions = object : DeviceConditions {
        override fun pauseReason() = pauseReason

        override fun pluggedIn() = pluggedIn

        override fun chargingHeld() = chargingHeld
    }
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
        SyncScheduler(workManager, settings, peers, state, history, clock, backgroundScope)

    private suspend fun SyncScheduler.applyCurrentPlan() = apply(plans().first())

    private fun pending(role: DeviceRole) = runs(role).filter { it.state == WorkInfo.State.ENQUEUED }

    /** Every run of [role]'s background job WorkManager knows; one without constraints may have started at once. */
    private fun runs(role: DeviceRole) = workManager.getWorkInfosForUniqueWork(SyncScheduler.nameOf(role)).get()

    private val onTheCharger = Data.Builder().putBoolean(SyncScheduler.PLUGGED_IN, true).build()

    private fun pairWithConsumer(schedule: SyncSchedule? = null) {
        peers.register(PairedPeer(consumer, "Pixel 2 XL", DeviceRole.CONSUMER), "192.168.1.20:47420")
        peers.recordConsumer(consumer, ConsumerFacts(schedule = schedule))
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
        val sync = producerSync { ProducerState.Failed(SyncFailure.UNREACHABLE) }

        runSyncWorker(sync, wifi = emptyList()) shouldBe ListenableWorker.Result.retry()
        coVerify(exactly = 0) { sync.syncAndWait(any()) }
        history.record.value.problem shouldBe SyncProblem.NO_WIFI

        runSyncWorker(sync) shouldBe ListenableWorker.Result.retry()
        history.record.value.problem shouldBe SyncProblem.UNREACHABLE
    }

    @Test
    fun aHotPhoneWaitsToSync() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        val sync = producerSync { ProducerState.Idle() }
        pauseReason = PauseReason.HOT

        runSyncWorker(sync) shouldBe ListenableWorker.Result.retry()

        coVerify(exactly = 0) { sync.syncAndWait(any()) }
        history.record.value.problem shouldBe SyncProblem.HOT
    }

    @Test
    fun aSyncPausedByALowBatteryCarriesOnLater() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        now = ZonedDateTime.parse("2026-10-15T03:02+02:00[Europe/Zurich]")
        state.markHandled(DeviceRole.PRODUCER, millis("2026-10-14T03:00+02:00"))
        val sync = producerSync { pauseWhen ->
            pauseReason = PauseReason.LOW_BATTERY // The battery runs low while sending.
            pauseWhen() shouldBe true
            ProducerState.Idle(SyncResult(sent = 3, cancelled = true))
        }

        runSyncWorker(sync) shouldBe ListenableWorker.Result.retry()

        history.record.value.problem shouldBe SyncProblem.LOW_BATTERY
        state.lastHandledDue(DeviceRole.PRODUCER) shouldBe millis("2026-10-14T03:00+02:00") // 03:00 isn't done.
    }

    @Test
    fun onTheChargerWithTheBatteryHeldBelowFullARunGoesAheadWithoutWorkManagersChargingCheck() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        val sync = producerSync { ProducerState.Idle() }
        chargingHeld = true // Samsung's Protect battery: plugged in, held at 85 %.

        runSyncWorker(sync) shouldBe ListenableWorker.Result.success()

        coVerify(exactly = 0) { sync.syncAndWait(any()) } // WorkManager would stop this run at once.
        runs(DeviceRole.PRODUCER).single().constraints.requiresCharging() shouldBe false
    }

    @Test
    fun aRunOnTheChargerThatIsUnpluggedWaitsForTheNextCharge() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        now = ZonedDateTime.parse("2026-10-15T03:02+02:00[Europe/Zurich]")
        state.markHandled(DeviceRole.PRODUCER, millis("2026-10-14T03:00+02:00"))
        chargingHeld = true
        val sync = producerSync { pauseWhen ->
            pauseWhen() shouldBe false
            pluggedIn = false // Unplugged while sending.
            pauseWhen() shouldBe true
            ProducerState.Idle(SyncResult(sent = 3, cancelled = true))
        }

        runSyncWorker(sync, input = onTheCharger) shouldBe ListenableWorker.Result.success()

        state.lastHandledDue(DeviceRole.PRODUCER) shouldBe millis("2026-10-14T03:00+02:00") // 03:00 isn't done.
        val next = pending(DeviceRole.PRODUCER).single()
        next.constraints.requiresCharging() shouldBe true
        next.initialDelayMillis shouldBe 0 // Missed: at the next charge.
    }

    @Test
    fun aReceiveWindowOnTheChargerClosesWhenUnplugged() = runTest {
        settings.setRole(DeviceRole.CONSUMER)
        now = ZonedDateTime.parse("2026-10-15T02:55+02:00[Europe/Zurich]")
        state.markHandled(DeviceRole.CONSUMER, millis("2026-10-14T03:00+02:00"))
        val receiver = mockk<ConsumerReceiver>(relaxed = true) {
            every { state } returns MutableStateFlow(ReceiverState(listening = true))
            every { hold(any()) } answers { pluggedIn = false } // Unplugged once listening.
        }

        runReceiveWindow(receiver, input = onTheCharger) shouldBe ListenableWorker.Result.success()

        verify { receiver.release(any()) }
        state.lastHandledDue(DeviceRole.CONSUMER) shouldBe millis("2026-10-14T03:00+02:00") // 03:00 isn't done.
        pending(DeviceRole.CONSUMER).single().constraints.requiresCharging() shouldBe true
    }

    @Test
    fun aScheduledSyncThatDidntRunIsReportedUntilASyncGetsThrough() = runTest {
        val scheduler = scheduler()
        state.markHandled(DeviceRole.PRODUCER, millis("2026-10-14T03:00+02:00"))

        now = ZonedDateTime.parse("2026-10-15T03:40+02:00[Europe/Zurich]") // Background jobs may start late.
        scheduler.missedSync(SyncSchedule.Default).shouldBeNull()

        now = ZonedDateTime.parse("2026-10-15T07:00+02:00[Europe/Zurich]")
        scheduler.missedSync(SyncSchedule.Default) shouldBe
            ZonedDateTime.parse("2026-10-15T03:00+02:00[Europe/Zurich]")
        scheduler.missedSync(SyncSchedule.WhileCharging).shouldBeNull()

        history.recordSync(millis("2026-10-15T06:30+02:00"), waitingForSpace = 0) // Sync now.
        scheduler.missedSync(SyncSchedule.Default).shouldBeNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent
    @Test
    fun receivingOutsideAReceiveWindowRunsAsForegroundWork() = runTest {
        val states = MutableStateFlow(ReceiverState(listening = true))
        scheduler().followReceiving(states)
        fun receiving() = workManager.getWorkInfosForUniqueWork("receive-now").get()
        runCurrent()
        receiving() shouldBe emptyList()

        states.value = states.value.copy(sessions = 1) // A sending phone connected while MediaSync is open.
        runCurrent()
        receiving().size shouldBe 1

        states.value = states.value.copy(sessions = 0, directLinkOpen = true) // Turbo receive waits for one.
        runCurrent()
        receiving().size shouldBe 1 // The same work keeps going.
    }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceTimeBy
    @Test
    fun foregroundReceivingEndsAfterAQuietMinute() = runTest {
        val states =
            MutableStateFlow(ReceiverState(listening = true, sessions = 1, lastActivityMillis = clock.millis()))
        val receiver = mockk<ConsumerReceiver>(relaxed = true) { every { state } returns states }
        val done = async {
            runWorker<ReceiveNowWorker> { appContext, parameters ->
                ReceiveNowWorker(appContext, parameters, receiver, peers, notifications, clock)
            }
        }
        runCurrent()
        verify { receiver.hold(any()) }

        // The session ends; a minute later, still nothing.
        states.value = states.value.copy(sessions = 0)
        now = now.plusSeconds(30)
        advanceTimeBy(10_000)
        done.isCompleted shouldBe false
        now = now.plusSeconds(31)
        advanceTimeBy(10_000)

        done.await() shouldBe ListenableWorker.Result.success()
        verify { receiver.release(any()) }
    }

    @Test
    fun aReceiveWindowClosesWhileThePhoneIsHotAndOpensAgainLater() = runTest {
        settings.setRole(DeviceRole.CONSUMER)
        now = ZonedDateTime.parse("2026-10-15T02:55+02:00[Europe/Zurich]")
        val receiver = mockk<ConsumerReceiver>(relaxed = true) {
            every { state } returns MutableStateFlow(ReceiverState(listening = true))
            every { hold(any()) } answers { pauseReason = PauseReason.HOT } // Gets hot once listening.
        }

        runReceiveWindow(receiver) shouldBe ListenableWorker.Result.retry()
        verify { receiver.release(any()) }

        runReceiveWindow(receiver) shouldBe ListenableWorker.Result.retry() // Still hot: doesn't open.
        verify(exactly = 1) { receiver.hold(any()) }
        state.lastHandledDue(DeviceRole.CONSUMER).shouldBeNull()
    }

    @Test
    fun aDoneSyncIsRememberedAndTheNextOneQueued() = runTest {
        settings.setRole(DeviceRole.PRODUCER)
        pairWithConsumer(SyncSchedule.Default)
        now = ZonedDateTime.parse("2026-10-15T03:02+02:00[Europe/Zurich]")
        val sync = producerSync { ProducerState.Idle(SyncResult(sent = 2)) }

        runSyncWorker(sync) shouldBe ListenableWorker.Result.success()

        state.lastHandledDue(DeviceRole.PRODUCER) shouldBe millis("2026-10-15T03:00+02:00")
        pending(DeviceRole.PRODUCER) shouldHaveSize 1
    }

    @Test
    fun theSyncHistoryOutlivesTheAppAndASyncClearsTheProblem() {
        history.recordProblem(SyncProblem.UNREACHABLE)
        SyncHistory(context).record.value shouldBe SyncRecord(problem = SyncProblem.UNREACHABLE)

        history.recordSync(atMillis = 1_000, waitingForSpace = 3)

        SyncHistory(context).record.value shouldBe SyncRecord(lastSyncMillis = 1_000, waitingForSpace = 3)
        history.record.value shouldBe SyncRecord(lastSyncMillis = 1_000, waitingForSpace = 3)
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

    /** A sync that ends as [end] says, given the worker's `pauseWhen`. */
    private fun producerSync(end: (pauseWhen: () -> Boolean) -> ProducerState) = mockk<ProducerSync> {
        every { state } returns MutableStateFlow(ProducerState.Idle())
        coEvery { syncAndWait(any()) } answers { end(firstArg()) }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runSyncWorker(
        sync: ProducerSync,
        wifi: List<InetAddress> = listOf(InetAddress.getByName("192.168.1.30")),
        input: Data = Data.EMPTY,
    ): ListenableWorker.Result {
        val scheduler = scheduler()
        return runWorker<ScheduledSyncWorker>(input) { appContext, parameters ->
            val network = AddressProvider { wifi }
            ScheduledSyncWorker(
                appContext,
                parameters,
                sync,
                peers,
                network,
                conditions,
                scheduler,
                history,
                notifications,
            )
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runReceiveWindow(
        receiver: ConsumerReceiver,
        input: Data = Data.EMPTY,
    ): ListenableWorker.Result {
        val scheduler = scheduler()
        return runWorker<ReceiveWindowWorker>(input) { appContext, parameters ->
            ReceiveWindowWorker(
                appContext,
                parameters,
                receiver,
                settings,
                peers,
                conditions,
                scheduler,
                notifications,
                clock,
            )
        }
    }

    private val notifications = mockk<SyncNotifications>(relaxed = true)

    private suspend inline fun <reified W : CoroutineWorker> runWorker(
        input: Data = Data.EMPTY,
        crossinline create: (Context, WorkerParameters) -> W,
    ): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<W>(context)
            .setInputData(input)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = create(appContext, workerParameters)
                },
            )
            .build()
        return worker.doWork()
    }
}
