/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import io.github.danyk20.mediasync.core.util.Lock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import okio.Buffer
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout

internal actual fun newTestPipe(maxBufferSize: Long, timeoutMillis: Long): TestPipe =
    IosTestPipe(maxBufferSize, timeoutMillis)

/**
 * A pipe over one buffer and one [Lock]. Writers wait while the buffer is full, readers while it is empty; both give
 * up with an IOException after [timeoutMillis]. Like okio's Pipe: a closed sink reads as end of stream after the
 * buffer drained; cancel discards the buffer and fails every later read and write.
 */
private class IosTestPipe(private val maxBufferSize: Long, private val timeoutMillis: Long) : TestPipe {
    private val lock = Lock()
    private val buffer = Buffer()
    private var sinkClosed = false
    private var canceled = false

    /** Waits for a change until [deadline]; call only inside [Lock.withLock]. */
    private fun awaitChange(deadline: TimeSource.Monotonic.ValueTimeMark) {
        val remaining = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds
        if (remaining <= 0 || !lock.await(remaining)) throw IOException("timeout")
    }

    override val source: Source = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val deadline = TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
            return lock.withLock {
                while (!canceled && buffer.size == 0L && !sinkClosed) awaitChange(deadline)
                when {
                    canceled -> throw IOException("canceled")
                    buffer.size == 0L -> -1L
                    else -> buffer.read(sink, minOf(byteCount, buffer.size)).also { lock.signalAll() }
                }
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = cancel()
    }

    override val sink: Sink = object : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            val deadline = TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
            var left = byteCount
            while (left > 0) {
                val written = lock.withLock {
                    check(!sinkClosed) { "closed" }
                    while (!canceled && buffer.size >= maxBufferSize) awaitChange(deadline)
                    if (canceled) throw IOException("canceled")
                    val chunk = minOf(left, maxBufferSize - buffer.size)
                    buffer.write(source, chunk)
                    lock.signalAll()
                    chunk
                }
                left -= written
            }
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = lock.withLock {
            sinkClosed = true
            lock.signalAll()
        }
    }

    override fun cancel() = lock.withLock {
        canceled = true
        buffer.clear()
        lock.signalAll()
    }
}
