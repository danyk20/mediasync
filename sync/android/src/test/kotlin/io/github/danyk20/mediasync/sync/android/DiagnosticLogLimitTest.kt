/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.model.LogSize
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import java.io.File
import java.nio.file.Files
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticLogLimitTest {

    private val directory: File = Files.createTempDirectory("diagnostics").toFile()
    private val log = FileDiagnosticLog(directory, Clock.systemUTC(), maxBytes = LogSize.MAX_BYTES)
    private val settings = FakeSettingsRepository()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun theLogFollowsTheChosenSize() = runTest(UnconfinedTestDispatcher()) {
        val entry = "x".repeat(1_000)
        repeat(2_000) { log.log(Area.APP, entry) }
        log.read().length shouldBeGreaterThan 2_000_000 // Nothing dropped until the chosen size is known.

        DiagnosticLogLimit(settings, log, backgroundScope).start()
        log.read().length shouldBeLessThanOrEqual LogSize.DEFAULT_BYTES.toInt()

        settings.setDiagnosticsLogBytes(LogSize.MIN_BYTES)
        log.read().length shouldBeLessThanOrEqual LogSize.MIN_BYTES.toInt()
    }
}
