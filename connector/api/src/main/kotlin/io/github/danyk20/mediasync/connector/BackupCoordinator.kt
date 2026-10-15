/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts backups as soon as items arrive, independent of the sync schedule (docs/PLAN.md §9.6), and keeps the state
 * of items handed to another app up to date as that app removes them (§9.3.1).
 */
class BackupCoordinator(
    private val registry: ConnectorRegistry,
    private val orchestrator: BackupOrchestrator,
    /** How long the backed-up folder has to stay unchanged before it's checked: a cleanup removes many at once. */
    private val settleMillis: Long = SETTLE_MILLIS,
) {
    /**
     * Backs up to each of [accounts] while [scope] lives: at once, then whenever [arrivals] says something was
     * received. Runs for one account never overlap; changes that come in meanwhile lead to one more run.
     */
    fun start(scope: CoroutineScope, accounts: Flow<List<ConnectorAccount>>, arrivals: Flow<Unit>): Job = scope.launch {
        accounts.distinctUntilChanged().collectLatest { current ->
            coroutineScope { current.forEach { account -> launch { follow(account, arrivals) } } }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun follow(account: ConnectorAccount, arrivals: Flow<Unit>) = coroutineScope {
        val connector = registry.connectorFor(account)
        if (connector == null) {
            Diagnostics.log(Area.BACKUP, "No connector ${account.connectorId} in this version; not backing up to it")
            return@coroutineScope
        }
        Diagnostics.log(Area.BACKUP, "Backing up to ${account.connectorId}")
        val turn = Mutex()
        launch {
            arrivals.onStart { emit(Unit) }.conflate().collect {
                turn.withLock { guarded { orchestrator.backUp(account, connector) } }
            }
        }
        val app = connector.deviceApp() ?: return@coroutineScope
        launch {
            app.folderChanges().onStart { emit(Unit) }.debounce(settleMillis).collect {
                turn.withLock { guarded { orchestrator.reconcile(account, app) } }
            }
        }
    }

    /** A run that fails (say, the database is busy) is logged; the next arrival or change tries again. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(run: suspend () -> Any) {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log(Area.BACKUP, "Backup run failed", e)
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 5_000L
    }
}
