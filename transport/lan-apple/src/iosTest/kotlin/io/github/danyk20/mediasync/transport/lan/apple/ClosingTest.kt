/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

private const val ONE_MIB = 1 shl 20
private const val TWO_MIB = 2 shl 20
private const val CLOSE_AFTER_MILLIS = 600L
private const val CONNECTIONS = 100

/** Descriptors may come and go a little (Network.framework caches some); a leak grows with every connection. */
private const val DESCRIPTOR_SLACK = 8

/** Closing from either side, end of stream, and no descriptor left behind. */
class ClosingTest {
    @Test
    fun closeWhileAReadIsPendingUnblocksIt() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            // The peer never answers and the read timeout is long, so only the close can end the read in time.
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.silentPort,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(readMillis = LONG_READ_MILLIS),
            )
            // The close comes from another thread while the read below is blocked. (Not a dispatch_after block: K/N
            // cannot bridge a Kotlin lambda passed as a dispatch block and aborts when it runs.)
            val closer = CoroutineScope(Dispatchers.Default).launch {
                delay(CLOSE_AFTER_MILLIS)
                connection.close()
            }
            try {
                val started = TimeSource.Monotonic.markNow()
                val error = assertFailsWith<IOException> { connection.source.readByte() }
                val elapsed = started.elapsedNow()
                val early = "The read failed before the close: $elapsed"
                assertTrue(elapsed >= (CLOSE_AFTER_MILLIS / 2).milliseconds, early)
                assertTrue(elapsed < CLOSE_BOUND_MILLIS.milliseconds, "The read stayed blocked for $elapsed")
                assertTrue(error.message.orEmpty().contains("closed"), "The read failed with: ${error.message}")
            } finally {
                closer.cancel()
                connection.close()
            }
        }
        recordPass("closeWhileAReadIsPendingUnblocksIt")
    }

    @Test
    fun closeIsIdempotentAndLaterReadsAndWritesFail() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect("127.0.0.1", server.port, loadedIdentity(identityDir), server.pin)
            connection.close()
            connection.close()
            assertFailsWith<IOException> { connection.source.readByte() }
            assertFailsWith<IOException> {
                connection.sink.writeUtf8("after close")
                connection.sink.flush()
            }
        }
        recordPass("closeIsIdempotentAndLaterReadsAndWritesFail")
    }

    @Test
    fun aServerClosingMidStreamEndsInAnIOException() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.dropPort,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(readMillis = LONG_READ_MILLIS),
            )
            try {
                // The peer sends 1 MiB and closes: asking for 2 MiB must end in end-of-stream, not a hang.
                val started = TimeSource.Monotonic.markNow()
                assertFailsWith<IOException> { connection.source.readByteArray(TWO_MIB.toLong()) }
                val hung = "The read hung for ${started.elapsedNow()}"
                assertTrue(started.elapsedNow() < CLOSE_BOUND_MILLIS.milliseconds, hung)
            } finally {
                connection.close()
            }
        }
        recordPass("aServerClosingMidStreamEndsInAnIOException")
    }

    @Test
    fun aServerClosingAfterItsDataEndsTheStreamWithEof() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.dropPort,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(readMillis = LONG_READ_MILLIS),
            )
            try {
                assertEquals(ONE_MIB, connection.source.readByteArray(ONE_MIB.toLong()).size)
                assertEquals(-1L, connection.source.read(Buffer(), 1L), "The stream should end after the peer's data")
            } finally {
                connection.close()
            }
        }
        recordPass("aServerClosingAfterItsDataEndsTheStreamWithEof")
    }

    @Test
    fun manySequentialConnectionsDoNotLeakDescriptors() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val identity = loadedIdentity(identityDir)
            connectHelloAndClose(server, identity) // warm up: first use creates lasting descriptors
            val before = openDescriptors()
            val retainedBefore = CallbackRetention.count()
            repeat(CONNECTIONS) { connectHelloAndClose(server, identity) }
            val grew = openDescriptors() - before
            assertTrue(grew <= DESCRIPTOR_SLACK, "$grew descriptors stayed open after $CONNECTIONS connections")
            // Each closed connection must leave the callback retention once Network.framework has finished with it.
            val retained = CallbackRetention.count() - retainedBefore
            assertTrue(retained == 0, "$retained closed connections are still retained")
        }
        recordPass("manySequentialConnectionsDoNotLeakDescriptors")
    }

    private fun connectHelloAndClose(server: InteropServerInfo, identity: AppleIdentity) {
        val connection = NwLanClient.connect("127.0.0.1", server.port, identity, server.pin)
        try {
            helloExchange(connection)
        } finally {
            connection.close()
        }
    }

    /** The number of open file descriptors of this process (Darwin lists them under /dev/fd). */
    private fun openDescriptors(): Int = FileSystem.SYSTEM.list("/dev/fd".toPath()).size

    private companion object {
        const val LONG_READ_MILLIS = 30_000L

        /** A close must end a blocked read well before the read's own timeout. */
        const val CLOSE_BOUND_MILLIS = 15_000L
    }
}
