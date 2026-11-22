/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.core.media.MediaFolder
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.feature.producer.ProducerDevice
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.TestFileState
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

// The desktop app only receives, but the shared graph holds the sending screens' ViewModels too: these keep them
// buildable. No screen the desktop shows reaches them.

/** [ProducerDevice] on a computer: it never sends. */
@ContributesBinding(AppScope::class)
@Inject
class DesktopProducerDevice : ProducerDevice {
    override fun canReadLibrary() = false

    override fun canReadLocation() = false

    override fun canNotify() = true

    override fun batteryRestricted() = false

    override fun syncNow(turbo: Boolean) = Unit

    override fun missedSync(schedule: SyncSchedule): ZonedDateTime? = null

    override val syncRecord: Flow<SyncRecord> = flowOf(SyncRecord())

    override val mobileDataSent: Flow<Long> = flowOf(0L)

    override val testFile: Flow<TestFileState> = flowOf(TestFileState())

    override fun removeTestFile() = Unit
}

/** [MediaLibrary] on a computer: empty. */
@ContributesBinding(AppScope::class)
@Inject
class NoMediaLibrary : MediaLibrary {
    override suspend fun folders(): List<MediaFolder> = emptyList()

    override fun changes(): Flow<Unit> = emptyFlow()
}
