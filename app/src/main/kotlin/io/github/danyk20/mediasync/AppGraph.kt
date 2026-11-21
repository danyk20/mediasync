/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.feature.quickaccess.QuickAccess
import io.github.danyk20.mediasync.feature.share.ShareTarget
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.DiagnosticLogLimit
import io.github.danyk20.mediasync.sync.android.MetroWorkerFactory
import io.github.danyk20.mediasync.sync.android.SyncScheduler

/** The app's dependency graph: everything contributed to [AppScope], built once per process. */
@DependencyGraph(AppScope::class)
interface AppGraph :
    ViewModelGraph,
    MetroAppComponentProviders {
    val scheduler: SyncScheduler
    val receiver: ConsumerReceiver
    val diagnosticLog: FileDiagnosticLog
    val diagnosticLogLimit: DiagnosticLogLimit
    val cloudBackup: CloudBackup
    val quickAccess: QuickAccess
    val shareTarget: ShareTarget
    val workerFactory: MetroWorkerFactory

    @DependencyGraph.Factory
    fun interface Factory {
        /** The application context: the one [Context] the graph provides. */
        fun create(@Provides context: Context): AppGraph
    }
}
