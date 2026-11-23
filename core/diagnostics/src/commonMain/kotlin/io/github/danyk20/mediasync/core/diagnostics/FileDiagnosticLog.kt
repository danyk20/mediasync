/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * The diagnostic log in [directory]: one line per entry, written on a background thread so logging never slows
 * the caller. It's a rolling log of at most [maxBytes] in all, 1 MB unless set otherwise: new entries go to
 * `diagnostics.log`, which, at a tenth of the limit, becomes the numbered `diagnostics.<n>.log`; when the limit is
 * reached, the oldest of these goes, so about a tenth of the log at once and never the whole of it.
 *
 * [limitTo] changes the limit while the app runs; a smaller one drops the oldest entries straight away.
 *
 * Files are read and written through [fileSystem]; entries are stamped with [clock] in [zone].
 */
class FileDiagnosticLog(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val clock: Clock,
    private val zone: TimeZone,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) : DiagnosticLog {

    private val current = directory / "diagnostics.log"
    private val writer = newSerialWriter()

    // Touched on the writer only.
    private var maxBytes = maxBytes.coerceAtLeast(1)
    private var older: ArrayDeque<Segment>? = null
    private var total = 0L

    override fun log(area: Area, message: String) {
        val line = "${formatTimestamp(clock.now(), zone)} ${area.label.padEnd(AREA_WIDTH)} ${message.oneLine()}\n"
        writer.execute { append(line.encodeToByteArray()) }
    }

    /** Keeps at most [bytes] from now on: a smaller limit drops the oldest entries at once. */
    fun limitTo(bytes: Long) {
        writer.execute {
            maxBytes = bytes.coerceAtLeast(1)
            trim(chunk = 0)
        }
    }

    /** Waits, for a second at most, until what's been logged is written: before the app ends. */
    fun flush() = writer.await(FLUSH_MILLIS)

    /** Waits, without a time limit, until every entry logged so far is on disk. For tests. */
    internal fun drain() {
        writer.submit { }
    }

    /** Everything kept, oldest first, once what's being written is written. */
    fun read(): String = writer.submit {
        segments().joinToString("") { textOrEmpty(it.file) } + textOrEmpty(current)
    }

    /** The last [count] entries, oldest first. */
    fun recent(count: Int): List<String> = read().lineSequence().filter { it.isNotEmpty() }.toList().takeLast(count)

    fun clear() {
        writer.submit {
            segments().forEach { delete(it.file) }
            delete(current)
            older = ArrayDeque()
            total = 0
        }
    }

    private fun append(line: ByteArray) {
        val segments = segments()
        fileSystem.createDirectories(directory)
        val length = sizeOf(current)
        if (length > 0 && length + line.size > segmentBytes()) {
            val number = (segments.lastOrNull()?.number ?: 0) + 1
            val full = directory / "diagnostics.$number.log"
            if (rename(current, full)) segments.addLast(Segment(number, full))
        }
        fileSystem.appendingSink(current).buffer().use { it.write(line) }
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
                total = sizeOf(current)
            } else if (sizeOf(oldest.file) <= excess) {
                total -= sizeOf(oldest.file)
                delete(oldest.file)
                segments.removeFirst()
            } else {
                total -= dropOldestLines(oldest.file, excess)
            }
        }
    }

    /** Drops at least [bytes] from the start of [file], up to the end of a line; returns how much it dropped. */
    private fun dropOldestLines(file: Path, bytes: Long): Long {
        val length = sizeOf(file)
        if (length == 0L) return 0
        val start = bytes.coerceIn(1, length) - 1
        // From `start`, up to and including the next newline (or to the end of the file).
        val cut = fileSystem.source(file).buffer().use { input ->
            input.skip(start)
            val newline = input.indexOf(NEWLINE)
            if (newline < 0) length else start + newline + 1
        }
        if (cut >= length) {
            delete(file)
            return length
        }
        val kept = directory / "diagnostics.tmp"
        fileSystem.source(file).buffer().use { input ->
            input.skip(cut)
            fileSystem.sink(kept).buffer().use { output -> output.writeAll(input) }
        }
        rename(kept, file)
        return cut
    }

    /** The numbered files, oldest first: read from [directory] once, then kept up to date. */
    private fun segments(): ArrayDeque<Segment> = older ?: ArrayDeque(
        fileSystem.listOrNull(directory).orEmpty()
            .mapNotNull { file -> NUMBERED.matchEntire(file.name)?.let { Segment(it.groupValues[1].toLong(), file) } }
            .sortedBy { it.number },
    ).also { found ->
        older = found
        total = found.sumOf { sizeOf(it.file) } + sizeOf(current)
    }

    private fun segmentBytes() = (maxBytes / SEGMENTS).coerceAtLeast(1)

    private fun sizeOf(file: Path): Long = fileSystem.metadataOrNull(file)?.size ?: 0L

    private fun textOrEmpty(file: Path): String =
        if (fileSystem.exists(file)) fileSystem.read(file) { readUtf8() } else ""

    /** A file that can't be deleted stays, as with `File.delete`, which only returned false. */
    private fun delete(file: Path) {
        runCatching { fileSystem.delete(file) }
    }

    /** Renames [from] to [to], replacing [to]; false if it can't, as `File.renameTo` was. */
    private fun rename(from: Path, to: Path): Boolean = runCatching { fileSystem.atomicMove(from, to) }.isSuccess

    private fun String.oneLine() = replace('\n', ' ').replace('\r', ' ')

    private data class Segment(val number: Long, val file: Path)

    companion object {
        /** How much is kept unless the user chooses otherwise: 1 MB. */
        const val DEFAULT_MAX_BYTES = 1024L * 1024

        /** The log is kept in about this many files, so the oldest tenth goes at once. */
        private const val SEGMENTS = 10
        private const val AREA_WIDTH = 8
        private const val FLUSH_MILLIS = 1_000L
        private const val NEWLINE: Byte = 10 // '\n'
        private val NUMBERED = Regex("""diagnostics\.(\d{1,18})\.log""")
    }
}
