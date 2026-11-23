/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The dispatcher for the blocking reads and writes of the fakes and the sessions in tests. `Dispatchers.IO` is not
 * visible from commonMain on every target, so each platform names it.
 */
internal expect val blockingIo: CoroutineDispatcher

/**
 * A scope whose coroutines may block a thread on a pipe or a socket, as the sessions do. Cancel it when the test is
 * over.
 */
fun blockingScope(): CoroutineScope = CoroutineScope(SupervisorJob() + blockingIo)
