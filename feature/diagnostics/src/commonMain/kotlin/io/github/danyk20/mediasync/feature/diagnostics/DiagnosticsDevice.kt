/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import io.github.danyk20.mediasync.sync.android.Fact
import io.github.danyk20.mediasync.sync.android.TestFileState
import kotlinx.coroutines.flow.StateFlow

/** What the diagnostics screen needs from the phone: the report and the log, and the test file waiting to be sent. */
interface DiagnosticsDevice {
    /** The test file waiting and the last one checked. */
    val testFile: StateFlow<TestFileState>

    /** This phone, how MediaSync is set up on it and what the system allows it. */
    suspend fun facts(): List<Fact>

    /** The newest [count] entries of the log. */
    suspend fun recent(count: Int): List<String>

    /** The report and the whole log, as one text. */
    suspend fun export(): String

    /** Empties the log. */
    suspend fun clear()

    /** Adds a test file of [bytes], to send first in the next sync. */
    fun addTestFile(bytes: Long)

    /** Removes the test file waiting, before it's sent. */
    fun removeTestFile()
}
