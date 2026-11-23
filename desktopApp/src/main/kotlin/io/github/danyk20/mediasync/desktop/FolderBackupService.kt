/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupCoordinator
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import io.github.danyk20.mediasync.connector.folder.FolderDescriptor
import io.github.danyk20.mediasync.connector.folder.FolderSettings
import io.github.danyk20.mediasync.core.database.BackupDao
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.feature.backup.FolderBackup
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration as KDuration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * A computer's backup (docs/PLAN.md §9, M8): what the phones send lands in the inbox, and this copies it into the
 * folder the user chose, through the folder connector and the same pipeline the phone uses (select what is waiting,
 * upload, record a receipt). It runs at start-up, after each received item, and every [retryEvery], so a disk or
 * share that is not there yet (the folder connector never creates its root) is tried again until it is.
 *
 * Once a copy is verified in the folder, the file in the inbox is deleted to save space ([InboxCleaner]); never before.
 */
@Suppress("LongParameterList") // Collaborators, one each.
class FolderBackupService(
    private val settings: SettingsRepository,
    private val registry: ConnectorRegistry,
    private val orchestrator: BackupOrchestrator,
    ledger: BackupLedger,
    private val backups: BackupDao,
    private val received: ReceivedItemDao,
    inbox: Path,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val copies: CopyGate,
    private val retryEvery: KDuration = 5.minutes,
    private val checkEvery: KDuration = 30.seconds,
) {
    private val cleaner = InboxCleaner(ledger, received, inbox, clock)
    private val started = AtomicBoolean()
    private val arrivals = Channel<Unit>(Channel.CONFLATED)
    private var feed: Job? = null
    private var copying: Job? = null
    private var cleanup: Job? = null

    /** Starts copying while [scope] lives; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        feed = scope.launch {
            merge(received.observeCount().map { }, ticks()).collect { arrivals.trySend(Unit) }
        }
        val accounts = settings.backupFolder.map { listOfNotNull(accountFor(it)) }
        // Fresh read of the source of truth (DataStore), not the flow above, which lags the write.
        val isCurrent: suspend (ConnectorAccount) -> Boolean = { account ->
            accountFor(settings.backupFolder.first()) == account
        }
        copying = BackupCoordinator(registry, orchestrator).start(scope, accounts, arrivals.receiveAsFlow(), isCurrent)
        cleanup = scope.launch { cleanUpAfterCopies() }
    }

    /**
     * Stops copying for quitting. Nothing new starts; a copy in progress finishes, for up to [STOP_WAIT_MILLIS]; then
     * the periodic checks and the copying end. Safe before [start] and when called twice.
     */
    suspend fun stop() {
        copies.close()
        feed?.cancel()
        cleanup?.cancel()
        if (!copies.awaitIdle(STOP_WAIT_MILLIS)) {
            Diagnostics.log(Area.BACKUP, "A copy was still running at quit; stopping it")
        }
        copying?.cancelAndJoin()
    }

    /** How the copying stands, as the backup screen and the home screen show it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun overview(): Flow<FolderBackup> = settings.backupFolder.flatMapLatest { path ->
        if (path == null) return@flatMapLatest flowOf(FolderBackup())
        val id = ACCOUNT_ID.value
        val today = midnights().flatMapLatest { backups.observeBackedUpSince(id, it) }
        combine(
            backups.observeStateCounts(id),
            today,
            backups.observeWaiting(id),
            reachable(path),
        ) { counts, sinceMidnight, waiting, reachable ->
            val count = counts.associate { it.state to it.count }
            FolderBackup(
                path = path,
                reachable = reachable,
                today = sinceMidnight,
                copied = (count[BackupState.BACKED_UP.name] ?: 0) + (count[BackupState.VERIFIED.name] ?: 0),
                waiting = waiting,
            )
        }
    }

    /** After each change in what was copied, and now and then: delete the inbox files whose copy checks out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun cleanUpAfterCopies() {
        settings.backupFolder.map { accountFor(it) }.distinctUntilChanged().flatMapLatest { account ->
            if (account == null) {
                emptyFlow()
            } else {
                merge(backups.observeStateCounts(account.id.value).map { }, ticks()).map { account }
            }
        }.conflate().collect { account ->
            guarded { cleaner.clean(account.id) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed pass is logged; the next change or tick tries again.
    private suspend fun guarded(run: suspend () -> Unit) {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log(Area.BACKUP, "Cleaning the inbox failed", e)
        }
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            delay(retryEvery)
            emit(Unit)
        }
    }

    /** Whether the folder is there; asked off the main thread, as a share that has gone away can block. */
    private fun reachable(path: String): Flow<Boolean> = flow {
        while (true) {
            emit(
                try {
                    Files.isDirectory(Paths.get(path))
                } catch (_: InvalidPathException) {
                    false
                },
            )
            delay(checkEvery)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** The start of today in the computer's time zone, and again at each midnight. */
    private fun midnights(): Flow<Long> = flow {
        while (true) {
            val now = ZonedDateTime.now(clock)
            val midnight = now.toLocalDate().atStartOfDay(now.zone)
            emit(midnight.toInstant().toEpochMilli())
            delay(Duration.between(now, midnight.plusDays(1)).toMillis().coerceAtLeast(1))
        }
    }

    private fun accountFor(path: String?): ConnectorAccount? = path?.let {
        ConnectorAccount(
            id = ACCOUNT_ID,
            connectorId = FolderDescriptor.id,
            displayName = FolderDescriptor.displayName,
            createdAtMillis = 0,
            settings = mapOf(FolderSettings.FOLDER to it),
        )
    }

    private companion object {
        /** One account for the folder, whichever folder it is: changing the folder only changes where new items go. */
        val ACCOUNT_ID = AccountId("folder")

        /** How long quitting waits for a copy in progress to finish. */
        const val STOP_WAIT_MILLIS = 4_000L
    }
}
