/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal actual fun newSerialWriter(): SerialWriter = ChannelWriter()

/**
 * One coroutine takes the tasks from a queue: one at a time, in order. A failing task is dropped: logging must
 * never end the app.
 *
 * The writer runs on its own single thread, not on Dispatchers.Default. submit and await block their caller with
 * runBlocking, so if the writer shared the Default pool, a burst of blocked callers could occupy every Default
 * thread and the writer could never run: a deadlock. A dedicated thread cannot be taken by those callers.
 */
private class ChannelWriter : SerialWriter {
    private val tasks = Channel<() -> Unit>(Channel.UNLIMITED)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val writerDispatcher = newSingleThreadContext("diagnostics")

    init {
        CoroutineScope(SupervisorJob() + writerDispatcher).launch {
            for (task in tasks) runCatching { task() }
        }
    }

    override fun execute(task: () -> Unit) {
        tasks.trySend(task)
    }

    override fun <T> submit(task: () -> T): T {
        val done = CompletableDeferred<Result<T>>()
        execute { done.complete(runCatching { task() }) }
        return runBlocking { done.await() }.getOrThrow()
    }

    override fun await(timeoutMillis: Long) {
        val done = CompletableDeferred<Unit>()
        execute { done.complete(Unit) }
        runCatching { runBlocking { withTimeoutOrNull(timeoutMillis) { done.await() } } }
    }
}
