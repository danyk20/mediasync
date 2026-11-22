/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.MobileDataUsage
import io.github.danyk20.mediasync.sync.android.SyncHistory
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import io.github.danyk20.mediasync.sync.android.TestFileState
import io.github.danyk20.mediasync.sync.android.TestFiles
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow

/** [ProducerDevice] on Android: permissions, WorkManager, and what the sync keeps in the app's preferences. */
@ContributesBinding(AppScope::class)
@Inject
class AndroidProducerDevice(
    private val context: Context,
    private val scheduler: SyncScheduler,
    history: SyncHistory,
    private val setup: BackgroundSetup,
    mobileDataUsage: MobileDataUsage,
    private val testFiles: TestFiles,
) : ProducerDevice {
    override fun canReadLibrary() = MediaPermissions.canReadLibrary(context)

    override fun canReadLocation() = MediaPermissions.canReadLocation(context)

    override fun canNotify() = setup.canNotify()

    override fun batteryRestricted() = setup.batteryRestricted()

    override fun syncNow(turbo: Boolean) = scheduler.syncNow(turbo)

    override fun missedSync(schedule: SyncSchedule): ZonedDateTime? = scheduler.missedSync(schedule)

    override val syncRecord: Flow<SyncRecord> = history.record

    override val mobileDataSent: Flow<Long> = mobileDataUsage.sentThisMonth

    override val testFile: Flow<TestFileState> = testFiles.state

    override fun removeTestFile() = testFiles.remove()
}
