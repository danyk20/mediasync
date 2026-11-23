/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

/** Runs tasks one at a time, in the order they were given, away from the caller. */
internal interface SerialWriter {
    /** Runs [task] later; does not wait. */
    fun execute(task: () -> Unit)

    /** Runs [task] after everything given before, and returns what it returned (or throws what it threw). */
    fun <T> submit(task: () -> T): T

    /** Waits, for [timeoutMillis] at most, until everything given so far has run. Never throws. */
    fun await(timeoutMillis: Long)
}

internal expect fun newSerialWriter(): SerialWriter
