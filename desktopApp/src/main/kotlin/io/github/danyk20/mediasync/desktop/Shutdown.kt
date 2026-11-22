/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How the app stops, in order: the backup copier (a copy in progress finishes), [scopes] the caller started, the
 * receiver (servers and the mDNS advertisement), everything still running in the graph's application scope — waited
 * for, not just cancelled — and only then the database. Closing the database while a query still runs crashes the
 * process inside SQLite.
 */
internal fun DesktopGraph.shutdownSteps(vararg scopes: CoroutineScope): List<() -> Unit> = listOf(
    { runBlocking { folderBackup.stop() } },
    { stopAndWait(*scopes) },
    { receiver.stop() },
    { stopAndWait(applicationScope) },
    { database.close() },
)

private fun stopAndWait(vararg scopes: CoroutineScope) = runBlocking {
    withTimeoutOrNull(SCOPE_STOP_MILLIS) {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
    }
}

private const val SCOPE_STOP_MILLIS = 5_000L
