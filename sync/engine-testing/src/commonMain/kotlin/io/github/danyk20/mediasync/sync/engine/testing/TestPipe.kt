/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import okio.Sink
import okio.Source

/** One in-memory pipe: what is written to [sink] is read from [source], in order. */
internal interface TestPipe {
    val source: Source
    val sink: Sink

    /** Fails the pending and later reads and writes, as okio's Pipe.cancel does. */
    fun cancel()
}

/**
 * A pipe holding at most [maxBufferSize] bytes, whose reads and writes give up with an IOException after
 * [timeoutMillis]. JVM: okio's Pipe. Kotlin/Native's Okio has no Pipe: iOS has its own.
 */
internal expect fun newTestPipe(maxBufferSize: Long, timeoutMillis: Long): TestPipe
