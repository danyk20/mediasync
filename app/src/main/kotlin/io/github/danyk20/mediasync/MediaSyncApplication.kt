/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics

class MediaSyncApplication :
    Application(),
    Configuration.Provider,
    MetroApplication {

    /** Lazy: ContentProviders and components are created before [onCreate], and ask for the graph then. */
    val graph: AppGraph by lazy { createGraphFactory<AppGraph.Factory>().create(this) }

    override val appComponentProviders: MetroAppComponentProviders
        get() = graph

    /** Background work gets its dependencies from the graph (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(graph.workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        val graph = graph
        Diagnostics.install(graph.diagnosticLog)
        Diagnostics.logCrashes(graph.diagnosticLog::flush)
        // Keeps the log to the size chosen in Settings (docs/PLAN.md §11).
        graph.diagnosticLogLimit.start()
        Diagnostics.log(Area.APP, "Started: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        // Keeps the background sync in step with the role and the schedule (docs/PLAN.md §10).
        graph.scheduler.start()
        // Receiving with MediaSync open, or during Turbo receive, carries on with the screen off (§7).
        graph.scheduler.followReceiving(graph.receiver.state)
        // Backs up what arrives as soon as it's stored, whatever the schedule (§9.6).
        graph.cloudBackup.start()
        // The home-screen widget shows how syncing stands.
        graph.quickAccess.start()
        // Lists MediaSync in the share sheet on a paired sending phone.
        graph.shareTarget.start()
    }
}
