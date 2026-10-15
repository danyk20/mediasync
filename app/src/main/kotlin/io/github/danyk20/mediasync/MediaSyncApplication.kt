/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.feature.quickaccess.QuickAccess
import io.github.danyk20.mediasync.feature.share.ShareTarget
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
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

    @Inject
    lateinit var receiver: ConsumerReceiver

    @Inject
    lateinit var diagnosticLog: FileDiagnosticLog

    @Inject
    lateinit var cloudBackup: CloudBackup

    @Inject
    lateinit var quickAccess: QuickAccess

    @Inject
    lateinit var shareTarget: ShareTarget

    /** Background work gets its dependencies from Hilt (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(diagnosticLog)
        Diagnostics.log(Area.APP, "Started: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        // Keeps the background sync in step with the role and the schedule (docs/PLAN.md §10).
        scheduler.start()
        // Receiving with MediaSync open, or during Turbo receive, carries on with the screen off (§7).
        scheduler.followReceiving(receiver.state)
        // Backs up what arrives as soon as it's stored, whatever the schedule (§9.6).
        cloudBackup.start()
        // The home-screen widget shows how syncing stands.
        quickAccess.start()
        // Lists MediaSync in the share sheet on a paired sending phone.
        shareTarget.start()
    }
}
