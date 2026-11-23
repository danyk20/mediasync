/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

/**
 * Kotlin/Native's `Dispatchers.IO` is internal in this coroutines version, so the sessions get their own pool. Each
 * blocked read or write holds one of these threads, and a sync runs at most a few dozen of them at once. The pool is
 * never closed: it lives as long as the process, as `Dispatchers.IO` does.
 */
// Each sync uses at most 8 threads (4 senders + 4 readers). If more than ~16 syncs ran at once, the readers could
// starve. Only one sync runs at a time today.
private const val IO_THREADS = 32

@OptIn(DelicateCoroutinesApi::class)
internal actual val ioDispatcher: CoroutineDispatcher = newFixedThreadPoolContext(IO_THREADS, "mediasync-io")
