/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.FrameType
import io.github.danyk20.mediasync.sync.protocol.ProtocolException
import io.github.danyk20.mediasync.sync.protocol.TestFileBegin
import io.github.danyk20.mediasync.sync.protocol.TestFileEnd
import io.github.danyk20.mediasync.sync.protocol.TestFileReady
import io.github.danyk20.mediasync.sync.protocol.TestFileResult
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import okio.ByteString
import okio.ByteString.Companion.toByteString

// The test file (docs/PLAN.md §7, *Test file*): a file of the size the user chose, sent first in the next sync to
// test the speed and the connection. Its bytes are made while they're sent, so it takes no space on the sending
// phone. The receiving phone writes it where only MediaSync can see it, checks it arrived exactly as sent, and
// deletes it: it never becomes a photo or video there, and nothing backs it up.

/** A test file waiting to be sent: [size] bytes, made from [seed]. */
data class TestFile(val size: Long, val seed: Long) {
    init {
        require(size > 0) { "A test file has at least one byte" }
    }
}

/** How the test file went to the receiving phone. */
enum class TestFilePath(internal val wire: Int) {
    WIFI(TestFileBegin.Path.WIFI),
    TURBO(TestFileBegin.Path.TURBO),

    /** Over the internet, directly between the phones. */
    INTERNET(TestFileBegin.Path.INTERNET),

    /** Over the internet, through the relay. */
    RELAY(TestFileBegin.Path.RELAY),
    ;

    internal companion object {
        fun of(wire: Int): TestFilePath = entries.firstOrNull { it.wire == wire } ?: WIFI
    }
}

/** What the receiving phone found. */
enum class TestFileStatus {
    /** All of it arrived, exactly as sent. */
    MATCHED,

    /** It arrived, but not exactly as sent. */
    MISMATCHED,

    /** The receiving phone couldn't write it while it came. */
    NOT_STORED,
}

/**
 * A test file that arrived and was checked: [bytes] of the [size] sent arrived in [micros], from the first byte to
 * the last, as the receiving phone measured them, over [path].
 */
data class TestFileOutcome(
    val status: TestFileStatus,
    val size: Long,
    val bytes: Long,
    val micros: Long,
    val path: TestFilePath,
) {
    val megabytesPerSecond: Double
        get() = if (micros <= 0L) 0.0 else bytes / BYTES_PER_MEGABYTE / (micros / MICROS_PER_SECOND)

    private companion object {
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val MICROS_PER_SECOND = 1e6
    }
}

/** Why a test file wasn't checked in this sync: it keeps waiting. */
enum class TestFileHeld {
    /** The receiving phone runs a version of MediaSync without test files: it needs updating. */
    RECEIVER_OUTDATED,

    /** It doesn't fit in the receiving phone's free space now. */
    NO_SPACE,

    /** The receiving phone can't write it now. */
    NOT_WRITABLE,

    /** The sync stopped while it was being sent. */
    STOPPED,
}

/** Producer side: the test file waiting to be sent, and what became of it. Calls are blocking, on an I/O thread. */
interface TestFileQueue {
    /** The test file waiting now; `null` if there's none. */
    fun waiting(): TestFile?

    /** The receiving phone checked [file] and deleted it: it no longer waits. */
    fun checked(file: TestFile, outcome: TestFileOutcome)

    /** [file] wasn't checked in this sync, for [reason]: it keeps waiting. */
    fun held(file: TestFile, reason: TestFileHeld)
}

/** How a producer session sends a test file it's handed: over [path], reporting to [queue]. */
data class TestFileRoute(val path: TestFilePath, val queue: TestFileQueue)

/**
 * Consumer side: where test files are written while they're checked. Somewhere only MediaSync can see, never the
 * gallery, and nothing there is backed up or handed on.
 */
interface TestFileStore {
    /** A new, empty file for one test file. */
    fun open(): IncomingTestFile

    /** A test file from [producer] was checked, and is deleted already. */
    fun checked(producer: DeviceId, outcome: TestFileOutcome)

    /** Deletes test files left from before, which a crash or a killed process didn't get to delete. */
    fun cleanLeftovers() = Unit
}

/** One test file being received. [delete] removes it, also when it's closed already; [close] alone doesn't. */
interface IncomingTestFile : Closeable {
    fun write(bytes: ByteArray, offset: Int, byteCount: Int)

    fun delete()
}

/**
 * A test file's bytes: a pseudo-random stream from its seed (SplitMix64), the same for the same seed. Media can't be
 * compressed, and neither can this.
 */
internal class TestFileBytes(seed: Long) {
    private var state = seed

    /** Fills the first [count] bytes of [into] with the next bytes. */
    fun fill(into: ByteArray, count: Int) {
        var i = 0
        while (i < count) {
            var value = next()
            val end = minOf(i + Long.SIZE_BYTES, count)
            while (i < end) {
                into[i++] = value.toByte()
                value = value ushr Byte.SIZE_BITS
            }
        }
    }

    private fun next(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr SHIFT_1)) * MIX_1
        z = (z xor (z ushr SHIFT_2)) * MIX_2
        return z xor (z ushr SHIFT_3)
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX_1 = -0x40a7b892e31b1a47L
        const val MIX_2 = -0x6b2fb644ecceee15L
        const val SHIFT_1 = 30
        const val SHIFT_2 = 27
        const val SHIFT_3 = 31
    }
}

/** How sending a test file ended. */
internal sealed interface TestFileSent {
    data class Checked(val outcome: TestFileOutcome) : TestFileSent

    data class Held(val reason: TestFileHeld) : TestFileSent
}

/**
 * Sends one test file over [channel], before anything else in the session: the consumer's replies are read here.
 * [onBytes]: these many more were sent. Stops between chunks once [shouldContinue] is `false`.
 */
internal class TestFileSender(
    private val channel: FrameChannel,
    private val path: TestFilePath,
    private val shouldContinue: () -> Boolean,
    private val onBytes: (Int) -> Unit,
) {
    fun send(file: TestFile, buffer: ByteArray): TestFileSent {
        channel.send(TestFileBegin(file.size, path.wire))
        val ready = channel.expect<TestFileReady>().status
        if (ready != TestFileReady.Status.OK) {
            return TestFileSent.Held(
                if (ready == TestFileReady.Status.NO_SPACE) TestFileHeld.NO_SPACE else TestFileHeld.NOT_WRITABLE,
            )
        }
        val sha256 = stream(file, buffer) ?: return TestFileSent.Held(TestFileHeld.STOPPED)
        channel.send(TestFileEnd(sha256))
        val result = channel.expect<TestFileResult>()
        val status = when (result.status) {
            TestFileResult.Status.MATCHED -> TestFileStatus.MATCHED
            TestFileResult.Status.FAILED -> TestFileStatus.NOT_STORED
            TestFileResult.Status.CANCELLED -> null
            else -> TestFileStatus.MISMATCHED
        }
        return status?.let { TestFileSent.Checked(TestFileOutcome(it, file.size, result.bytes, result.micros, path)) }
            ?: TestFileSent.Held(TestFileHeld.STOPPED)
    }

    /** Sends the bytes and returns their SHA-256; `null` if the sync stopped first, and the consumer was told. */
    private fun stream(file: TestFile, buffer: ByteArray): ByteString? {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = TestFileBytes(file.seed)
        var left = file.size
        var chunks = 0
        while (left > 0) {
            if (chunks++ % CHECK_EVERY_CHUNKS == 0 && !shouldContinue()) {
                channel.send(TestFileEnd(cancelled = true))
                channel.expect<TestFileResult>() // Deleted there.
                return null
            }
            val n = minOf(buffer.size.toLong(), left).toInt()
            bytes.fill(buffer, n)
            digest.update(buffer, 0, n)
            channel.sendData(buffer, n)
            left -= n
            onBytes(n)
        }
        return digest.digest().toByteString()
    }

    private companion object {
        /** Asking whether to go on costs something (the battery's state): every 8 MiB is soon enough. */
        const val CHECK_EVERY_CHUNKS = 8
    }
}

/**
 * Receives one test file announced with [begin] into [store], if it fits in [space], checks it and deletes it,
 * whatever happens: also when the connection drops. [onBytes]: these many more arrived. Returns how it went; `null`
 * if it wasn't taken, or the producer cancelled it.
 */
internal class TestFileReceiver(
    private val channel: FrameChannel,
    private val store: TestFileStore?,
    private val space: SpaceBudget,
    private val onBytes: (Int) -> Unit,
) {
    fun receive(begin: TestFileBegin, buffer: ByteArray): TestFileOutcome? {
        val size = begin.size
        val refusal = when {
            store == null || size <= 0 -> TestFileReady.Status.FAILED
            !space.tryReserve(size) -> TestFileReady.Status.NO_SPACE
            else -> null
        }
        if (refusal != null) return refuse(refusal)
        try {
            val file = store?.let(::openOrNull) ?: return refuse(TestFileReady.Status.FAILED)
            channel.send(TestFileReady(TestFileReady.Status.OK))
            return Transfer(size, file).use { it.run(buffer, TestFilePath.of(begin.path)) }
        } finally {
            space.release(size) // Deleted by now: its space is free again.
        }
    }

    private fun openOrNull(store: TestFileStore): IncomingTestFile? = try {
        store.open()
    } catch (_: IOException) {
        null
    }

    private fun refuse(status: Int): TestFileOutcome? {
        channel.send(TestFileReady(status))
        return null
    }

    /** One test file arriving into [file], which is deleted when this closes. */
    private inner class Transfer(private val size: Long, private val file: IncomingTestFile) : Closeable {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var writable = true
        private var length = 0L
        private var firstByteAt = 0L

        fun run(buffer: ByteArray, path: TestFilePath): TestFileOutcome? {
            val end = readUntilEnd(buffer)
            val micros = if (length == 0L) 0L else (System.nanoTime() - firstByteAt) / NANOS_PER_MICRO
            file.delete()
            val status = when {
                end.cancelled -> null
                !writable -> TestFileStatus.NOT_STORED
                length != size || digest.digest().toByteString() != end.sha256 -> TestFileStatus.MISMATCHED
                else -> TestFileStatus.MATCHED
            }
            channel.send(TestFileResult(status.toWire(), length, micros))
            return status?.let { TestFileOutcome(it, size, length, micros, path) }
        }

        private fun readUntilEnd(buffer: ByteArray): TestFileEnd {
            while (true) {
                val header = channel.nextHeader()
                if (header.type != FrameType.DATA) {
                    return when (val message = channel.readControl(header)) {
                        is TestFileEnd -> message
                        is Bye -> throw PeerEndedSessionException(message.reason)
                        else -> throw ProtocolException("Expected DATA or TEST_FILE_END while receiving a test file")
                    }
                }
                append(buffer, channel.readData(header, buffer))
            }
        }

        private fun append(buffer: ByteArray, count: Int) {
            if (length == 0L) firstByteAt = System.nanoTime()
            length += count
            onBytes(count)
            if (length > size) return // More than announced: it doesn't match.
            digest.update(buffer, 0, count)
            if (writable) {
                writable = try {
                    file.write(buffer, 0, count)
                    true
                } catch (_: IOException) {
                    false
                }
            }
        }

        override fun close() {
            file.delete()
        }
    }

    private fun TestFileStatus?.toWire(): Int = when (this) {
        null -> TestFileResult.Status.CANCELLED
        TestFileStatus.MATCHED -> TestFileResult.Status.MATCHED
        TestFileStatus.MISMATCHED -> TestFileResult.Status.MISMATCHED
        TestFileStatus.NOT_STORED -> TestFileResult.Status.FAILED
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}
