/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.TestFileState
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow

/**
 * What the sending phone's home screen needs from the phone beyond the sync itself: what the system allows, the
 * background work that syncs, and what that remembers. The Android app's implementation is `AndroidProducerDevice`;
 * the desktop app brings its own.
 */
interface ProducerDevice {
    /** Whether the whole photo library is readable. */
    fun canReadLibrary(): Boolean

    /** Whether locations stay in the photos that are sent. */
    fun canReadLocation(): Boolean

    /** Whether sync progress may be shown as a notification. */
    fun canNotify(): Boolean

    /** Whether the system restricts the app's battery use, which scheduled syncs at night need unrestricted. */
    fun batteryRestricted(): Boolean

    /** Sends in the background, so it carries on when the user leaves the app; [turbo] over a direct link. */
    fun syncNow(turbo: Boolean = false)

    /** The scheduled sync of [schedule] that should have happened by now but didn't; `null` if none. */
    fun missedSync(schedule: SyncSchedule): ZonedDateTime?

    /** The past syncs, as the home screen shows them. */
    val syncRecord: Flow<SyncRecord>

    /** Bytes sent over mobile data this month. */
    val mobileDataSent: Flow<Long>

    /** The test file waiting to go first in the next sync, and the last one checked. */
    val testFile: Flow<TestFileState>

    /** Removes the test file waiting, before it's sent. */
    fun removeTestFile()
}
