/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import java.io.File
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The diagnostic log in [directory]: one line per entry, written on a background thread so logging never slows
 * the caller. When the file reaches [maxBytes] it becomes the previous one, so at most about twice that is kept.
 */
class FileDiagnosticLog(
    private val directory: File,
    private val clock: Clock,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : DiagnosticLog {

    private val current = File(directory, "diagnostics.log")
    private val previous = File(directory, "diagnostics.1.log")
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diagnostics").apply { isDaemon = true }
    }

    override fun log(area: Area, message: String) {
        val line = "${FORMAT.format(ZonedDateTime.now(clock))} ${area.label.padEnd(AREA_WIDTH)} ${message.oneLine()}\n"
        writer.execute { append(line) }
    }

    /** Waits, for a second at most, until what's been logged is written: before the app ends. */
    fun flush() {
        runCatching { writer.submit {}.get(FLUSH_SECONDS, TimeUnit.SECONDS) }
    }

    /** Everything kept, oldest first, once what's being written is written. */
    fun read(): String = writer.submit<String> { previous.textOrEmpty() + current.textOrEmpty() }.get()

    /** The last [count] entries, oldest first. */
    fun recent(count: Int): List<String> = read().lineSequence().filter { it.isNotEmpty() }.toList().takeLast(count)

    fun clear() {
        writer.submit {
            current.delete()
            previous.delete()
        }.get()
    }

    private fun append(line: String) {
        directory.mkdirs()
        if (current.length() + line.length > maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(line)
    }

    private fun File.textOrEmpty() = if (exists()) readText() else ""

    private fun String.oneLine() = replace('\n', ' ').replace('\r', ' ')

    private companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024
        const val AREA_WIDTH = 8
        const val FLUSH_SECONDS = 1L
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xx")
    }
}
