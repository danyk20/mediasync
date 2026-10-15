/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.connector.testing.FakeConnectorFactory
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppFactory
import io.github.danyk20.mediasync.connector.testing.InMemoryBackupLedger
import io.github.danyk20.mediasync.connector.testing.account
import io.github.danyk20.mediasync.connector.testing.backupItem
import io.github.danyk20.mediasync.connector.testing.contentId
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupCoordinatorTest {

    private val ledger = InMemoryBackupLedger()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    private val apps = FakeDeviceAppFactory()
    private val registry = ConnectorRegistry(
        mapOf("test-cloud" to FakeConnectorFactory(), "test-app" to apps),
    )
    private val accounts = MutableStateFlow(listOf(account("test-cloud")))
    private val arrivals = MutableSharedFlow<Unit>()
    private val logged = mutableListOf<String>()

    init {
        Diagnostics.install { area, message -> logged += "${area.label}: $message" }
    }

    @AfterEach
    fun tearDown() = Diagnostics.install { _, _ -> }

    private fun TestScope.start(ledger: BackupLedger = this@BackupCoordinatorTest.ledger) {
        BackupCoordinator(registry, BackupOrchestrator(ledger, clock)).start(backgroundScope, accounts, arrivals)
        runCurrent()
    }

    private fun receiptOf(n: Int, connector: String = "test-cloud") = ledger.receipt(contentId(n), AccountId(connector))

    @Test
    fun backsUpAtOnceAndThenWheneverSomethingArrives() = runTest {
        ledger.received += backupItem(1)
        start()
        receiptOf(1)?.state shouldBe BackupState.BACKED_UP

        ledger.received += backupItem(2)
        arrivals.emit(Unit)
        runCurrent()

        receiptOf(2)?.state shouldBe BackupState.BACKED_UP
    }

    @Test
    fun followsTheAccountsAsTheyChange() = runTest {
        accounts.value = emptyList()
        ledger.received += backupItem(1)
        start()
        receiptOf(1).shouldBeNull()

        accounts.value = listOf(account("test-cloud"))
        runCurrent()
        receiptOf(1)?.state shouldBe BackupState.BACKED_UP

        accounts.value = emptyList()
        runCurrent()
        ledger.received += backupItem(2)
        arrivals.emit(Unit)
        runCurrent()
        receiptOf(2).shouldBeNull()
    }

    @Test
    fun itemsTheOtherAppRemovesAreNoticedOnceTheFolderSettles() = runTest {
        accounts.value = listOf(account("test-app"))
        ledger.received += backupItem(1)
        apps.connector.onPhone += backupItem(1).location
        start()
        receiptOf(1, "test-app")?.state shouldBe BackupState.HANDED_OVER
        advanceTimeBy(SETTLE_MILLIS + 1)
        apps.connector.checks shouldBe 1

        apps.connector.onPhone.clear() // Free up space in the other app.
        apps.connector.changes.emit(Unit)
        advanceTimeBy(SETTLE_MILLIS / 2)
        apps.connector.changes.emit(Unit) // It removes many items, one after another.
        advanceTimeBy(SETTLE_MILLIS / 2)
        receiptOf(1, "test-app")?.state shouldBe BackupState.HANDED_OVER

        advanceTimeBy(SETTLE_MILLIS)
        receiptOf(1, "test-app")?.state shouldBe BackupState.REMOVED_BY_APP
        apps.connector.checks shouldBe 2
    }

    @Test
    fun anAccountWhoseConnectorThisVersionLacksIsLeftAlone() = runTest {
        accounts.value = listOf(account("from-a-newer-version"))
        ledger.received += backupItem(1)

        start()

        logged shouldContain "backup: No connector from-a-newer-version in this version; not backing up to it"
    }

    @Test
    fun aRunThatFailsIsLoggedAndTheNextArrivalTriesAgain() = runTest {
        var busy = true
        val flaky = object : BackupLedger by ledger {
            override suspend fun pending(account: AccountId, limit: Int): List<BackupItem> {
                check(!busy) { "database busy" }
                return ledger.pending(account, limit)
            }
        }
        ledger.received += backupItem(1)
        start(flaky)
        logged shouldContain "backup: Backup run failed: IllegalStateException: database busy"

        busy = false
        arrivals.emit(Unit)
        runCurrent()

        receiptOf(1)?.state shouldBe BackupState.BACKED_UP
    }

    private companion object {
        const val SETTLE_MILLIS = 5_000L
    }
}
