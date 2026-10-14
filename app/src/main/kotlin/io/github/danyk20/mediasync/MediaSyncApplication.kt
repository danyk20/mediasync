/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class MediaSyncApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var scheduler: SyncScheduler

    /** Background work gets its dependencies from Hilt (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Keeps the background sync in step with the role and the schedule (docs/PLAN.md §10).
        scheduler.start()
    }
}
