/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import java.util.concurrent.TimeUnit
import okio.Pipe
import okio.Sink
import okio.Source

internal actual fun newTestPipe(maxBufferSize: Long, timeoutMillis: Long): TestPipe =
    JvmTestPipe(Pipe(maxBufferSize), timeoutMillis)

private class JvmTestPipe(private val pipe: Pipe, timeoutMillis: Long) : TestPipe {
    override val source: Source = pipe.source
    override val sink: Sink = pipe.sink

    init {
        pipe.source.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        pipe.sink.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun cancel() = pipe.cancel()
}
