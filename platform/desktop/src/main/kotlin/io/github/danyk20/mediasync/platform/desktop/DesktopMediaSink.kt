/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.core.metadata.ByteReader
import io.github.danyk20.mediasync.core.metadata.CaptureDateGuess
import io.github.danyk20.mediasync.core.metadata.VideoDates
import io.github.danyk20.mediasync.core.metadata.resolve
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.IncomingFile
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaSink
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import okio.ByteString
import okio.Source
import okio.source

/**
 * Stores received photos and videos in the local [inbox], inside the app's data folder, never straight in the user's
 * folder: a backup connector copies from the inbox later, so an unmounted NAS never stops
 * receiving. A file is written under [partialDir] as `<producer>-<fingerprint>.part`, which survives a restart so an
 * interrupted transfer resumes; it only appears in the inbox, complete and with its modification time, by an atomic
 * move on [IncomingFile.commit]. [inbox] and [partialDir] must be on one file system. Names: see [safeFileName]; a
 * name already taken gets ` (1)`, ` (2)` before its extension.
 */
class DesktopMediaSink(
    private val inbox: Path,
    private val partialDir: Path = inbox.resolveSibling("inbox-partial"),
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    private val names: (String) -> String = ::safeFileName,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : MediaSink {
    private val lock = Any()

    init {
        Files.createDirectories(inbox)
        Files.createDirectories(partialDir)
    }

    override fun availableBytes(): Long = (Files.getFileStore(inbox).usableSpace - reserveBytes).coerceAtLeast(0)

    override fun partialSize(producer: DeviceId, fingerprint: ByteString): Long {
        val file = partialOf(producer, fingerprint)
        return if (Files.isRegularFile(file)) Files.size(file) else 0
    }

    override fun open(producer: DeviceId, descriptor: MediaDescriptor, offset: Long): IncomingFile {
        val partial = partialOf(producer, descriptor.fingerprint)
        val channel = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            if (offset > channel.size()) throw IOException("Can't resume at $offset; only ${channel.size()} bytes kept")
            channel.truncate(offset) // Starting over (offset 0), or dropping what the sender didn't confirm.
            channel.position(offset)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return PartialFile(partial, channel, descriptor)
    }

    private fun partialOf(producer: DeviceId, fingerprint: ByteString): Path =
        partialDir.resolve("${producer.hex}-${fingerprint.hex()}.part")

    private inner class PartialFile(
        private val partial: Path,
        private val channel: FileChannel,
        private val descriptor: MediaDescriptor,
    ) : IncomingFile {
        private var open = true

        override fun existingBytes(): Source = partial.source()

        override fun write(bytes: ByteArray, offset: Int, byteCount: Int) {
            val buffer = ByteBuffer.wrap(bytes, offset, byteCount)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun commit(): String {
            channel.force(true)
            close()
            completeVideoDate(partial, descriptor, zone)
            (descriptor.meta.modifiedAtMillis ?: descriptor.meta.capturedAtMillis)?.let {
                Files.setLastModifiedTime(partial, java.nio.file.attribute.FileTime.fromMillis(it))
            }
            return publish(partial, names(descriptor.displayName)).toString()
        }

        override fun discard() {
            close()
            Files.deleteIfExists(partial)
        }

        override fun close() {
            if (!open) return
            open = false
            channel.close()
        }
    }

    /** Moves [partial] to a free name in the inbox. */
    private fun publish(partial: Path, name: String): Path = synchronized(lock) {
        val target = freeName(name)
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial, target)
        }
        target
    }

    private fun freeName(name: String): Path {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var candidate = inbox.resolve(name)
        var n = 1
        while (Files.exists(candidate)) {
            candidate =
                inbox.resolve("${name.substring(0, dot)} (${n++})${name.substring(dot)}")
        }
        return candidate
    }

    companion object {
        /** Receiving stops while less than this would remain free. */
        const val DEFAULT_RESERVE_BYTES = 1L * 1024 * 1024 * 1024
    }
}

/** Gives a video without a capture date the best one there is (docs/PLAN.md §6.2), in its movie header, in place. */
internal fun completeVideoDate(file: Path, descriptor: MediaDescriptor, zone: ZoneId) {
    val meta = descriptor.meta
    val date = CaptureDateGuess.resolve(meta.capturedAtMillis, descriptor.displayName, meta.modifiedAtMillis, zone)
        ?: return
    RandomAccessFile(file.toFile(), "rw").use { raf ->
        val size = raf.length()
        val reader = ByteReader { offset, count ->
            ByteArray(count).also {
                raf.seek(offset)
                raf.readFully(it)
            }
        }
        VideoDates.dateEdits(size, reader, date.epochMillis).forEach { edit ->
            raf.seek(edit.offset)
            raf.write(edit.bytes)
        }
    }
}

/**
 * A name safe on Windows, macOS and Linux: no path parts, no characters Windows forbids, no leading or trailing dots
 * or spaces, at most 200 characters, never empty or a reserved Windows device name.
 */
fun safeFileName(original: String): String {
    val leaf = original.substringAfterLast('/').substringAfterLast('\\')
    var name = leaf.map { if (it.code < FIRST_PRINTABLE || it in "<>:\"|?*") '_' else it }.joinToString("")
        .trim().trimStart('.').trimEnd('.', ' ')
    if (name.length > MAX_NAME) {
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= MAX_EXTENSION } ?: name.length
        name = name.substring(0, MAX_NAME - (name.length - dot)) + name.substring(dot)
    }
    if (name.isEmpty()) name = "file"
    if (name.substringBefore('.').uppercase() in RESERVED) name = "_$name"
    return name
}

private const val MAX_NAME = 200
private const val FIRST_PRINTABLE = 32
private const val MAX_EXTENSION = 16
private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
