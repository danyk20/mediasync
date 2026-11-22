/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The computer owns the household's schedule like the receiving phone: what the user sets is kept, and what it tells
 * each sending phone in a session (`DeviceInfo.local()`, as `ConsumerReceiver` reads it). It listens all the time, so
 * there are no receive windows to keep.
 */
class ScheduleServedTest {

    @TempDir
    lateinit var directory: Path

    private val graphs = TestGraphs()

    @AfterEach
    fun shutDownGraphs() = graphs.shutDown()

    private val graph by lazy {
        graphs.create(directory)
    }

    @Test
    fun `producers are told the default schedule until the user changes it`() {
        DesktopDeviceInfo(graph.settings).local().schedule shouldBe SyncSchedule.Default
    }

    @Test
    fun `a schedule the user sets is kept and told to the producers`() = runBlocking<Unit> {
        val weekly = SyncSchedule.Weekly(DayOfWeek.SUNDAY, LocalTime(2, 30))

        graph.settings.setSyncSchedule(weekly)

        DesktopDeviceInfo(graph.settings).local().schedule shouldBe weekly
        SyncSchedule.decode(weekly.encode()) shouldBe weekly
    }
}
