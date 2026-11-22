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
 * How the app stops, whichever role runs: the role's part first ([shutdownSteps] — a copy in progress finishes, the
 * sender's sync stops), then the receiver for good (a screen may still hold it), then everything still running in
 * [scopes] and the graph's application scope — waited for, not just cancelled — and only then the database. Closing
 * the database while a query still runs crashes the process inside SQLite.
 */
internal fun DesktopGraph.shutdownSteps(vararg scopes: CoroutineScope): List<() -> Unit> = shutdownSteps(
    roles,
    cancelScope = {
        receiver.stop()
        stopAndWait(*scopes, applicationScope)
    },
    closeDatabase = { database.close() },
)

private fun stopAndWait(vararg scopes: CoroutineScope) = runBlocking {
    withTimeoutOrNull(SCOPE_STOP_MILLIS) {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
    }
}

private const val SCOPE_STOP_MILLIS = 5_000L
