/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.sync.android.DiagnosticsReport
import io.github.danyk20.mediasync.sync.android.Fact
import io.github.danyk20.mediasync.sync.android.TestFileState
import io.github.danyk20.mediasync.sync.android.TestFiles
import kotlinx.coroutines.flow.StateFlow

/** [DiagnosticsDevice] on Android: [DiagnosticsReport] and [TestFiles]. */
@ContributesBinding(AppScope::class)
@Inject
class AndroidDiagnosticsDevice(private val report: DiagnosticsReport, private val testFiles: TestFiles) :
    DiagnosticsDevice {
    override val testFile: StateFlow<TestFileState> = testFiles.state

    override suspend fun facts(): List<Fact> = report.facts()

    override suspend fun recent(count: Int): List<String> = report.recent(count)

    override suspend fun export(): String = report.export()

    override suspend fun clear() = report.clear()

    override fun addTestFile(bytes: Long) = testFiles.add(bytes)

    override fun removeTestFile() = testFiles.remove()
}
