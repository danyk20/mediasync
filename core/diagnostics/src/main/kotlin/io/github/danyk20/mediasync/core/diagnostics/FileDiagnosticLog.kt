/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The diagnostic log in [directory]: one line per entry, written on a background thread so logging never slows
 * the caller. It's a rolling log of at most [maxBytes] in all, 1 MB unless set otherwise: new entries go to
 * `diagnostics.log`, which, at a tenth of the limit, becomes the numbered `diagnostics.<n>.log`; when the limit is
 * reached, the oldest of these goes, so about a tenth of the log at once and never the whole of it.
 *
 * [limitTo] changes the limit while the app runs; a smaller one drops the oldest entries straight away.
 */
class FileDiagnosticLog(private val directory: File, private val clock: Clock, maxBytes: Long = DEFAULT_MAX_BYTES) :
    DiagnosticLog {

    private val current = File(directory, "diagnostics.log")
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diagnostics").apply { isDaemon = true }
    }

    // Touched on the writer thread only.
    private var maxBytes = maxBytes.coerceAtLeast(1)
    private var older: ArrayDeque<Segment>? = null
    private var total = 0L

    override fun log(area: Area, message: String) {
        val line = "${FORMAT.format(ZonedDateTime.now(clock))} ${area.label.padEnd(AREA_WIDTH)} ${message.oneLine()}\n"
        writer.execute { append(line.toByteArray()) }
    }

    /** Keeps at most [bytes] from now on: a smaller limit drops the oldest entries at once. */
    fun limitTo(bytes: Long) {
        writer.execute {
            maxBytes = bytes.coerceAtLeast(1)
            trim(chunk = 0)
        }
    }

    /** Waits, for a second at most, until what's been logged is written: before the app ends. */
    fun flush() {
        runCatching { writer.submit {}.get(FLUSH_SECONDS, TimeUnit.SECONDS) }
    }

    /** Everything kept, oldest first, once what's being written is written. */
    fun read(): String = writer.submit<String> {
        segments().joinToString("") { it.file.textOrEmpty() } + current.textOrEmpty()
    }.get()

    /** The last [count] entries, oldest first. */
    fun recent(count: Int): List<String> = read().lineSequence().filter { it.isNotEmpty() }.toList().takeLast(count)

    fun clear() {
        writer.submit {
            segments().forEach { it.file.delete() }
            current.delete()
            older = ArrayDeque()
            total = 0
        }.get()
    }

    private fun append(line: ByteArray) {
        val segments = segments()
        directory.mkdirs()
        val length = current.length()
        if (length > 0 && length + line.size > segmentBytes()) {
            val number = (segments.lastOrNull()?.number ?: 0) + 1
            val full = File(directory, "diagnostics.$number.log")
            if (current.renameTo(full)) segments.addLast(Segment(number, full))
        }
        current.appendBytes(line)
        total += line.size
        trim(chunk = segmentBytes())
    }

    /**
     * Drops the oldest entries until at most [maxBytes] are kept. A numbered file goes whole when it's no bigger
     * than [chunk] (what's dropped at least); a bigger one, left from a larger limit, loses its oldest lines.
     */
    private fun trim(chunk: Long) {
        val segments = segments()
        while (total > maxBytes) {
            val excess = maxOf(total - maxBytes, chunk)
            val oldest = segments.firstOrNull()
            if (oldest == null) {
                dropOldestLines(current, total - maxBytes)
                total = current.length()
            } else if (oldest.file.length() <= excess) {
                total -= oldest.file.length()
                oldest.file.delete()
                segments.removeFirst()
            } else {
                total -= dropOldestLines(oldest.file, excess)
            }
        }
    }

    /** Drops at least [bytes] from the start of [file], up to the end of a line; returns how much it dropped. */
    private fun dropOldestLines(file: File, bytes: Long): Long {
        val length = file.length()
        if (length == 0L) return 0
        val cut = RandomAccessFile(file, "r").use { input ->
            input.seek(bytes.coerceIn(1, length) - 1)
            var byte = 0
            while (byte != '\n'.code && byte >= 0) byte = input.read()
            input.filePointer
        }
        if (cut >= length) {
            file.delete()
            return length
        }
        val kept = File(directory, "diagnostics.tmp")
        RandomAccessFile(file, "r").use { input ->
            input.seek(cut)
            kept.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var read = input.read(buffer)
                while (read >= 0) {
                    output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }
        kept.renameTo(file)
        return cut
    }

    /** The numbered files, oldest first: read from [directory] once, then kept up to date. */
    private fun segments(): ArrayDeque<Segment> = older ?: ArrayDeque(
        directory.listFiles().orEmpty()
            .mapNotNull { file -> NUMBERED.matchEntire(file.name)?.let { Segment(it.groupValues[1].toLong(), file) } }
            .sortedBy { it.number },
    ).also { found ->
        older = found
        total = found.sumOf { it.file.length() } + current.length()
    }

    private fun segmentBytes() = (maxBytes / SEGMENTS).coerceAtLeast(1)

    private fun File.textOrEmpty() = if (exists()) readText() else ""

    private fun String.oneLine() = replace('\n', ' ').replace('\r', ' ')

    private data class Segment(val number: Long, val file: File)

    companion object {
        /** How much is kept unless the user chooses otherwise: 1 MB. */
        const val DEFAULT_MAX_BYTES = 1024L * 1024

        /** The log is kept in about this many files, so the oldest tenth goes at once. */
        private const val SEGMENTS = 10
        private const val AREA_WIDTH = 8
        private const val FLUSH_SECONDS = 1L
        private const val BUFFER_BYTES = 64 * 1024
        private val NUMBERED = Regex("""diagnostics\.(\d{1,18})\.log""")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xx")
    }
}
