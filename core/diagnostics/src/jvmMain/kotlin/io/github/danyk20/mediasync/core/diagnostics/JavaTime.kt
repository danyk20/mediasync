/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import java.io.File
import java.time.Clock as JavaClock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.toKotlinTimeZone
import okio.FileSystem
import okio.Path.Companion.toOkioPath

// The same log over java.io and java.time, for the Android and desktop code that works in them.

/** The diagnostic log in [directory], entries stamped by [clock] (and its time zone). */
@Suppress("FunctionName")
fun FileDiagnosticLog(
    directory: File,
    clock: JavaClock,
    maxBytes: Long = FileDiagnosticLog.DEFAULT_MAX_BYTES,
): FileDiagnosticLog = FileDiagnosticLog(
    FileSystem.SYSTEM,
    directory.toOkioPath(),
    object : Clock {
        // A java.time.Clock's zone never changes, so it is read once below; the time is read per entry.
        override fun now(): Instant = Instant.fromEpochMilliseconds(clock.millis())
    },
    clock.zone.toKotlinTimeZone(),
    maxBytes,
)
