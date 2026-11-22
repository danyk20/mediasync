/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.navigation.MediaSyncNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val WINDOW_TITLE = "MediaSync"

/** The desktop app: always the receiving role (docs/PLAN.md §9, M8). */
fun main() {
    val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths.resolve())
    Diagnostics.install(graph.diagnosticLog)
    Diagnostics.logCrashes(graph.diagnosticLog::flush)
    println("MediaSync ${AppVersion.current}: data in ${graph.paths.root}")
    startReceiving(graph)
    graph.folderBackup.start()
    application {
        Window(onCloseRequest = ::exitApplication, title = WINDOW_TITLE) {
            DesktopRoot(graph)
        }
    }
}

/** What the window shows: the shared navigation in the receiving role, with the graph's ViewModels. */
@Composable
internal fun DesktopRoot(graph: DesktopGraph) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.viewModelFactory) {
        MediaSyncTheme {
            MediaSyncNavigation(role = DeviceRole.CONSUMER, openedLink = null, onLinkHandled = {})
        }
    }
}

/** Listens for phones from the start, with the window open or not. */
@Suppress("TooGenericExceptionCaught") // Whatever stops it from listening is logged; the window still opens.
private fun startReceiving(graph: DesktopGraph) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        graph.receiver.state.first { it.listening }.let { println("MediaSync: receiving on port ${it.port}") }
    }
    try {
        graph.receiver.hold("app")
    } catch (e: Exception) {
        println("MediaSync: not receiving: ${e.message}")
        Diagnostics.log(io.github.danyk20.mediasync.core.diagnostics.Area.APP, "Could not start receiving", e)
    }
}
