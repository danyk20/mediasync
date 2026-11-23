/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

// Each blocked read or write holds one of these threads. A test runs a handful of them at once.
private const val TEST_IO_THREADS = 32

@OptIn(DelicateCoroutinesApi::class)
internal actual val blockingIo: CoroutineDispatcher = newFixedThreadPoolContext(TEST_IO_THREADS, "mediasync-test-io")
