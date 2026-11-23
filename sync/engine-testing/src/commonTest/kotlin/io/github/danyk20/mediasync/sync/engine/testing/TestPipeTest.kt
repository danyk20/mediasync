/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.IOException

/** The pipe that the iOS test connections use; on the JVM the same tests pin okio's Pipe. */
class TestPipeTest {

    @Test
    fun bytesWrittenInOddChunksArriveInOrder() = runBlocking<Unit> {
        val pipe = newTestPipe(maxBufferSize = 64 * 1024, timeoutMillis = 10_000)
        val payload = Random(3).nextBytes(5 * 1024 * 1024)
        val writer = CoroutineScope(blockingIo).async {
            var offset = 0
            var step = 1
            while (offset < payload.size) {
                val count = minOf(step, payload.size - offset)
                pipe.sink.write(Buffer().write(payload, offset, count), count.toLong())
                offset += count
                step = step * 3 % 70_001 + 1
            }
            pipe.sink.close()
        }
        val received = Buffer()
        while (pipe.source.read(received, 8_192) != -1L) {
            // Read until the writer closed the pipe and the buffer drained.
        }
        writer.await()

        received.readByteArray() shouldBe payload
    }

    @Test
    fun aReadWithNothingToReadTimesOutWithAnIOException() {
        val pipe = newTestPipe(maxBufferSize = 1_024, timeoutMillis = 200)

        shouldThrow<IOException> { pipe.source.read(Buffer(), 1) }
    }

    @Test
    fun cancelFailsAReadThatIsWaiting() = runBlocking<Unit> {
        val pipe = newTestPipe(maxBufferSize = 1_024, timeoutMillis = 10_000)
        val reader = CoroutineScope(blockingIo).async { runCatching { pipe.source.read(Buffer(), 1) } }
        delay(100)

        pipe.cancel()

        reader.await().exceptionOrNull().shouldBeInstanceOf<IOException>()
    }
}
