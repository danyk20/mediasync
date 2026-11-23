/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException

private const val READ_TIMEOUT_MILLIS = 1_000L
private const val WRITE_TIMEOUT_MILLIS = 1_000L

/** Generous slack above a timeout: the bound is that the call returns, not that it returns on the millisecond. */
private const val SLACK_MILLIS = 10_000L

/** Enough to fill the socket buffers of a peer that never reads, so the writes stall. */
private const val MAX_WRITE_CHUNKS = 128

/** Each read and write is bounded: a peer that stalls must fail the call, never hang it. */
class TimeoutTest {
    @Test
    fun aSilentPeerTimesOutTheReadWithinItsBound() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.silentPort,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(readMillis = READ_TIMEOUT_MILLIS),
            )
            try {
                val started = TimeSource.Monotonic.markNow()
                val error = assertFailsWith<IOException> { connection.source.readByte() }
                val elapsed = started.elapsedNow()
                assertTrue(elapsed < (READ_TIMEOUT_MILLIS + SLACK_MILLIS).milliseconds, "The read took $elapsed")
                assertTrue(elapsed >= (READ_TIMEOUT_MILLIS / 2).milliseconds, "The read failed early after $elapsed")
                assertTrue(error.message.orEmpty().contains("timed out"), "The read failed with: ${error.message}")
            } finally {
                connection.close()
            }
        }
        recordPass("aSilentPeerTimesOutTheReadWithinItsBound")
    }

    @Test
    fun aStalledPeerTimesOutTheWriteWithinItsBound() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        val chunk = ByteArray(1 shl 20).also { Random(STALL_SEED).nextBytes(it) }
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.silentPort,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(writeMillis = WRITE_TIMEOUT_MILLIS),
            )
            try {
                val started = TimeSource.Monotonic.markNow()
                val error = assertFailsWith<IOException> {
                    repeat(MAX_WRITE_CHUNKS) { connection.sink.write(chunk) }
                    connection.sink.flush()
                }
                val elapsed = started.elapsedNow()
                assertTrue(elapsed < (WRITE_TIMEOUT_MILLIS + SLACK_MILLIS).milliseconds, "The write took $elapsed")
                assertTrue(error.message.orEmpty().contains("timed out"), "The write failed with: ${error.message}")
            } finally {
                connection.close()
            }
        }
        recordPass("aStalledPeerTimesOutTheWriteWithinItsBound")
    }
}

private const val STALL_SEED = 7L
