/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.connector.testing.FakeConnector
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppConnector
import io.github.danyk20.mediasync.connector.testing.InMemoryBackupLedger
import io.github.danyk20.mediasync.connector.testing.account
import io.github.danyk20.mediasync.connector.testing.backupItem
import io.github.danyk20.mediasync.connector.testing.contentId
import io.github.danyk20.mediasync.connector.testing.descriptor
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BackupOrchestratorTest {

    private val ledger = InMemoryBackupLedger()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val logged = mutableListOf<String>()
    private val cloud = account("test-cloud")
    private val app = account("test-app")

    init {
        Diagnostics.install { area, message -> logged += "${area.label}: $message" }
    }

    @AfterEach
    fun tearDown() = Diagnostics.install { _, _ -> }

    @Test
    fun everythingWaitingIsBackedUpAndGetsAReceipt() = runTest {
        ledger.received += (1..250).map { backupItem(it) }
        val connector = FakeConnector()

        val run = BackupOrchestrator(ledger, clock).backUp(cloud, connector)

        run shouldBe BackupRun(uploaded = 250)
        connector.uploads shouldBe (1..250).map(::contentId)
        ledger.receipt(contentId(7), cloud.id) shouldBe
            BackupReceipt(contentId(7), cloud.id, "remote-${contentId(7)}", BackupState.BACKED_UP, NOW)
        ledger.pending(cloud.id, 10).shouldBeEmpty()
        logged shouldContainExactly listOf("backup: test-cloud: 250 uploaded")
    }

    @Test
    fun eachAccountHasItsOwnReceipts() = runTest {
        ledger.received += backupItem(1)
        val orchestrator = BackupOrchestrator(ledger, clock)

        orchestrator.backUp(cloud, FakeConnector())
        val other = account("test-cloud", id = "second-account")
        orchestrator.backUp(other, FakeConnector()) shouldBe BackupRun(uploaded = 1)
        orchestrator.backUp(cloud, FakeConnector()) shouldBe BackupRun()
    }

    @Test
    fun whatTheServiceCantTakeIsSkippedWithoutAsking() = runTest {
        ledger.received += listOf(
            backupItem(1, bytes = ByteArray(300)),
            backupItem(2, bytes = ByteArray(10)),
            backupItem(3, mimeType = "video/mp4"),
        )
        val photosOnly =
            FakeConnector(descriptor(capabilities = emptySet(), limits = ConnectorLimits(maxPhotoBytes = 100)))

        val run = BackupOrchestrator(ledger, clock).backUp(cloud, photosOnly)

        run shouldBe BackupRun(uploaded = 1, skipped = 2)
        photosOnly.uploads shouldBe listOf(contentId(2))
        ledger.receipt(contentId(1), cloud.id)?.remoteId shouldBe "TOO_LARGE"
        ledger.receipt(contentId(3), cloud.id)?.remoteId shouldBe "UNSUPPORTED_TYPE"
        ledger.receipt(contentId(3), cloud.id)?.state shouldBe BackupState.SKIPPED
    }

    @Test
    fun uploadsArePreprocessedButAnotherAppGetsTheStoredFile() = runTest {
        ledger.received += backupItem(1, bytes = ByteArray(300))
        var prepared = 0
        val shrink = Preprocessor { item, _ ->
            prepared++
            item.copy(size = 50, content = { ByteArrayInputStream(ByteArray(50)) })
        }
        val sizes = mutableListOf<Long>()
        val cloudConnector = FakeConnector(descriptor(limits = ConnectorLimits(maxPhotoBytes = 100))).apply {
            answer = { item ->
                sizes += item.content.open().use { it.readBytes().size.toLong() }
                UploadResult.Uploaded("r", item.size)
            }
        }
        val device = FakeDeviceAppConnector().apply { onPhone += backupItem(1).location }

        BackupOrchestrator(ledger, clock, shrink).backUp(cloud, cloudConnector) shouldBe BackupRun(uploaded = 1)
        BackupOrchestrator(ledger, clock, shrink).backUp(app, device) shouldBe BackupRun(handedOver = 1)

        sizes shouldBe listOf(50L) // The smaller upload copy fits; the stored file didn't.
        prepared shouldBe 1
        ledger.receipt(contentId(1), app.id) shouldBe BackupReceipt(
            contentId(1),
            app.id,
            backupItem(1).location,
            BackupState.HANDED_OVER,
            NOW,
        )
    }

    @Test
    fun aRunStopsAtAFailureAndLeavesTheRestWaiting() = runTest {
        ledger.received += (1..5).map { backupItem(it) }
        val connector = FakeConnector().apply {
            answer = { item ->
                if (item.contentId == contentId(3)) {
                    UploadResult.Failed(ConnectorError.SignInNeeded)
                } else {
                    UploadResult.Uploaded("r", 1)
                }
            }
        }

        val run = BackupOrchestrator(ledger, clock).backUp(cloud, connector)

        run shouldBe BackupRun(uploaded = 2, stoppedBy = ConnectorError.SignInNeeded)
        ledger.pending(cloud.id, 10).map { it.contentId } shouldBe listOf(contentId(3), contentId(4), contentId(5))
        logged shouldContainExactly listOf("backup: test-cloud: 2 uploaded; stopped: sign-in needed")
    }

    @Test
    fun aLedgerThatForgetsReceiptsCantMakeARunGoOnForever() = runTest {
        val forgetful = object : BackupLedger by ledger {
            override suspend fun record(receipts: List<BackupReceipt>) = Unit
        }
        ledger.received += listOf(backupItem(1), backupItem(1), backupItem(2))
        val connector = FakeConnector()

        BackupOrchestrator(forgetful, clock, batchSize = 2).backUp(cloud, connector) shouldBe BackupRun(uploaded = 1)
        connector.uploads shouldBe listOf(contentId(1)) // Item 2 waits: the ledger kept offering item 1.
    }

    @Test
    fun nothingWaitingMeansNothingToLog() = runTest {
        BackupOrchestrator(ledger, clock).backUp(cloud, FakeConnector()) shouldBe BackupRun()
        logged.shouldBeEmpty()
    }

    @Test
    fun whatTheOtherAppRemovedIsBackedUpAndRemoved() = runTest {
        val device = FakeDeviceAppConnector()
        ledger.received += (1..4).map { backupItem(it) }
        device.onPhone += (1..4).map { backupItem(it).location }
        val orchestrator = BackupOrchestrator(ledger, clock)
        orchestrator.backUp(app, device)

        device.onPhone -= backupItem(1).location // Free up space.
        device.onPhone -= backupItem(2).location
        device.trash += backupItem(2).location // Deleted by hand, perhaps: not counted.
        val later = BackupOrchestrator(ledger, Clock.offset(clock, Duration.ofHours(1)))

        later.reconcile(app, device) shouldBe 1
        later.reconcile(app, device) shouldBe 0

        ledger.receipt(contentId(1), app.id) shouldBe BackupReceipt(
            contentId(1),
            app.id,
            backupItem(1).location,
            BackupState.REMOVED_BY_APP,
            backedUpAtMillis = NOW,
            checkedAtMillis = NOW + HOUR_MILLIS,
        )
        ledger.receipt(contentId(2), app.id)?.state shouldBe BackupState.HANDED_OVER
        logged.last() shouldBe "backup: test-app: 1 backed up and removed from the phone"
    }

    @Test
    fun withNothingHandedOverThereIsNothingToCheck() = runTest {
        val device = FakeDeviceAppConnector()

        BackupOrchestrator(ledger, clock).reconcile(app, device) shouldBe 0

        device.checks shouldBe 0
        ledger.receipt(contentId(1), app.id).shouldBeNull()
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val HOUR_MILLIS = 3_600_000L
    }
}
