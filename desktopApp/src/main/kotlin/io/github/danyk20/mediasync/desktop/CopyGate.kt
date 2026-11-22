/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.UploadResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The copies in progress, so that quitting can let one finish. Once [close]d no new copy starts: it is refused with a
 * cancellation, so nothing is copied after the app has begun to quit.
 */
class CopyGate {
    private val active = MutableStateFlow(0)

    @Volatile
    private var closed = false

    /** Runs one copy. Throws [CancellationException] instead once the gate is closed. */
    suspend fun <T> copy(block: suspend () -> T): T {
        active.update { it + 1 }
        try {
            if (closed) throw CancellationException("The app is quitting")
            return block()
        } finally {
            active.update { it - 1 }
        }
    }

    /** Refuses every copy from now on; the ones in progress carry on. */
    fun close() {
        closed = true
    }

    /** Waits until no copy is in progress. False if [timeoutMillis] passes first. */
    suspend fun awaitIdle(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { active.first { it == 0 } } != null
}

/** The folder connector's uploads go through [gate], so a copy can be waited for or refused at quit. */
class GatedCopyFactory(private val inner: BackupConnectorFactory, private val gate: CopyGate) :
    BackupConnectorFactory by inner {

    override fun create(account: ConnectorAccount): BackupConnector {
        val connector = inner.create(account)
        return object : BackupConnector by connector {
            override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult =
                gate.copy { connector.upload(item, progress) }
        }
    }
}
