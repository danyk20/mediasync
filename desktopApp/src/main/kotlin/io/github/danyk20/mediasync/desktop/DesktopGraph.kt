/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.platform.desktop.DesktopIdentityProvider
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver

/** The desktop app's dependency graph: everything contributed to [AppScope] plus the desktop's own bindings. */
@DependencyGraph(AppScope::class)
interface DesktopGraph : ViewModelGraph {
    val receiver: ConsumerReceiver
    val identity: DesktopIdentityProvider
    val diagnosticLog: FileDiagnosticLog
    val paths: AppPaths
    val viewModelFactory: MetroViewModelFactory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides paths: AppPaths): DesktopGraph
    }
}
