/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.danyk20.mediasync.core.database.ConsumerFacts
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsReportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, MediaSyncDatabase::class.java).allowMainThreadQueries().build()
    private val peers = PeerStore(database.peerDao())
    private val settings = FakeSettingsRepository()
    private val history = SyncHistory(context)
    private val directory: File = Files.createTempDirectory("diagnostics").toFile()
    private val clock = Clock.fixed(Instant.parse("2026-10-14T07:30:00Z"), ZoneId.of("Europe/Zurich"))
    private val log = FileDiagnosticLog(directory, clock)
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun report() = DiagnosticsReport(
        context,
        settings,
        peers,
        history,
        { listOf(InetAddress.getByName("192.168.1.37")) },
        DirectLinks.None,
        workManager,
        log,
        clock,
        wakeUps,
    )

    private val wakeUpStatus = MutableStateFlow<WakeUpStatus?>(null)
    private val wakeUps = mockk<WakeUpCheck> { every { status } returns wakeUpStatus }

    @Test
    fun aSendingPhoneSaysHowItsSetUpWithoutNamingAnyone() = runBlocking<Unit> {
        settings.setRole(DeviceRole.PRODUCER)
        settings.setPhotoDetails(PhotoDetails.REMOVE_LOCATION)
        peers.register(PairedPeer(deviceId(7), "Anna's Pixel 2 XL", DeviceRole.CONSUMER), "192.168.1.20:47420")
        val everySix = SyncSchedule.EveryHours(6, java.time.LocalTime.of(3, 0))
        peers.recordConsumer(deviceId(7), ConsumerFacts(schedule = everySix))
        history.recordProblem(SyncProblem.UNREACHABLE)

        val facts = report().facts().associate { it.label to it.value }

        facts["Role"] shouldBe "producer"
        facts["Sends to"] shouldBe deviceId(7).short()
        facts["Schedule"] shouldBe "hours:6@03:00 (the receiving phone's)"
        facts["Photo details"] shouldBe "remove_location"
        facts["Last sync"] shouldBe "never"
        facts["Last problem"] shouldBe "unreachable"
        facts["Local network"] shouldBe "192.168.1.x"
        facts["Turbo"] shouldBe "Wi-Fi Direct: unsupported; Wi-Fi Aware: no"
        facts.keys shouldContain "Battery use"
        facts.keys shouldContain "Work: scheduled-sync"
        facts.values.joinToString() shouldNotContain "Anna"
    }

    @Test
    fun aReceivingPhoneSaysHowManyPhonesSendToIt() = runBlocking<Unit> {
        settings.setRole(DeviceRole.CONSUMER)
        settings.setSyncSchedule(SyncSchedule.WhileCharging)
        peers.register(PairedPeer(deviceId(1), "Anna's Pixel", DeviceRole.PRODUCER), null)
        peers.register(PairedPeer(deviceId(2), "Tom's Galaxy", DeviceRole.PRODUCER), null)

        val facts = report().facts().associate { it.label to it.value }

        facts["Receives from"] shouldBe "2"
        facts["Schedule"] shouldBe "charging"
        facts.containsKey("Wake-ups") shouldBe false // Without internet sync, there are none to check.
    }

    @Test
    fun aReceivingPhoneWithInternetSyncSaysWhetherWakeUpsGetThrough() = runBlocking<Unit> {
        settings.setRole(DeviceRole.CONSUMER)
        settings.setRendezvousUrl("https://mediasync-rendezvous.anna.workers.dev")
        fun wakeUps() = runBlocking { report().facts().associate { it.label to it.value }["Wake-ups"] }

        wakeUps() shouldBe "not checked yet"
        wakeUpStatus.value = WakeUpStatus(clock.millis(), "wake_relay_failed: The wake relay answered HTTP 404")
        wakeUps() shouldBe
            "don't work: wake_relay_failed: The wake relay answered HTTP 404 (checked 2026-10-14 09:30 +02:00)"
        wakeUpStatus.value = WakeUpStatus(clock.millis())
        wakeUps() shouldBe "work (checked 2026-10-14 09:30 +02:00)"
    }

    @Test
    fun backgroundWorkIsDescribedWithItsNextRun() = runBlocking<Unit> {
        settings.setRole(DeviceRole.CONSUMER)
        val scheduler = SyncScheduler(workManager, settings, peers, ScheduleState(context), history, clock, this)
        scheduler.apply(BackgroundPlan(DeviceRole.CONSUMER, SyncSchedule.Default))

        val work = report().facts().single { it.label == "Work: receive-window" }.value

        work shouldContain WorkInfo.State.ENQUEUED.name.lowercase()
        // WorkManager counts from the real time; the time is shown with its UTC offset only.
        work shouldContain Regex("""next at \d{4}-\d\d-\d\d \d\d:\d\d [+-]\d\d:\d\d""")
        report().export() shouldNotContain "Europe" // A time zone's name hints at where the phone is.
    }

    @Test
    fun theExportHasTheReportAndTheWholeLog() = runBlocking<Unit> {
        log.log(Area.SEND, "Sync done in 4.2 s: sent 3")

        val export = report().export()

        export shouldContain "MediaSync diagnostics"
        export shouldContain "Android: "
        export shouldContain "Sync done in 4.2 s: sent 3"
        report().recent(5).single() shouldContain "Sync done"
        report().clear()
        report().recent(5) shouldBe emptyList()
    }

    @Test
    fun logLinesArePlainAndShort() {
        bytes(512) shouldBe "512 B"
        bytes(12_582_912) shouldBe "12.0 MB"
        bytes(3L * 1024 * 1024 * 1024) shouldBe "3.00 GB"
        duration(Duration.ofMinutes(372)) shouldBe "6 h 12 min"
        duration(Duration.ofSeconds(42)) shouldBe "42 s"
        stopReason(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING) shouldBe "unplugged"
        stopReason(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) shouldBe "daily foreground limit (Android 15+)"
        TransferProgress(2, 5, 1024, 4096, "a.jpg").describe() shouldBe "item 2 of 5, 1.0 kB of 4.0 kB"
        null.describe() shouldBe "before the first item"
        SyncResult(sent = 2, bytesSent = 2048, failed = 1, withheld = 3, cancelled = true).describe() shouldBe
            "sent 2 (2.0 kB), failed 1, withheld 3, stopped early"
    }
}
