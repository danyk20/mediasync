/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

/** Enough of the stack trace to find the place, with the release's R8 mapping. */
private const val CRASH_FRAMES = 4

/**
 * Logs a crash before Android ends the app: what was thrown and where, so a shared log says why the app closed.
 * [flush] waits until the entry is written. Android's own handling (ending the app) follows as before.
 */
fun Diagnostics.logCrashes(flush: () -> Unit) {
    val android = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            val where = error.stackTrace.take(CRASH_FRAMES).joinToString(" ← ")
            log(Area.APP, "Crashed on ${thread.name}: ${describe(error)} at $where")
            flush()
        }
        android?.uncaughtException(thread, error)
    }
}
