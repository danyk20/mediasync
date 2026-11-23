/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal actual fun newSerialWriter(): SerialWriter = ExecutorWriter()

/** One daemon thread runs the tasks in order, so logging never slows the caller and never blocks app exit. */
private class ExecutorWriter : SerialWriter {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diagnostics").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) = executor.execute(task)

    override fun <T> submit(task: () -> T): T = executor.submit<T> { task() }.get()

    override fun await(timeoutMillis: Long) {
        runCatching { executor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS) }
    }
}
