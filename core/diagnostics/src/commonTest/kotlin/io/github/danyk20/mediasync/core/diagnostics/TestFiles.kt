/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import okio.FileSystem
import okio.Path

/** A new, empty folder in the system temporary directory. */
internal fun tempDirectory(): Path =
    (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "diagnostics-test-${Random.nextLong().toULong()}").also {
        FileSystem.SYSTEM.createDirectories(it)
    }

/** The bytes of all files in this folder. */
internal fun Path.stored(): Long = FileSystem.SYSTEM.list(this).sumOf { FileSystem.SYSTEM.metadata(it).size ?: 0L }

internal fun Path.fileCount(): Int = FileSystem.SYSTEM.list(this).size

internal class FixedClock(private val at: Instant) : Clock {
    override fun now(): Instant = at
}
