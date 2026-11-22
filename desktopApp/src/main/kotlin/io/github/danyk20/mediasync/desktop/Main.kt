/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.navigation.MediaSyncNavigation
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val WINDOW_TITLE = "MediaSync"

/** The desktop app: always the receiving role (docs/PLAN.md §9, M8). */
fun main() {
    val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths.resolve())
    Diagnostics.install(graph.diagnosticLog)
    Diagnostics.logCrashes(graph.diagnosticLog::flush)
    println("MediaSync ${AppVersion.current}: data in ${graph.paths.root}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val starter = startReceiving(graph, scope)
    watchNetwork(graph, scope)
    graph.folderBackup.start()
    val shutdown = AppShutdown(
        listOf(
            { runBlocking { graph.folderBackup.stop() } }, // before the database closes: a copy finishes first
            { scope.cancel() },
            { graph.receiver.stop() }, // closes the servers and the mDNS advertisement
            { graph.database.close() },
        ),
    )
    application {
        val trayAvailable = remember { trayAvailable() }
        var visible by remember { mutableStateOf(true) }
        val state by starter.state.collectAsState()
        val quit: () -> Unit = {
            visible = false // Gone at once; the shutdown runs behind it, off the UI thread.
            thread(name = "mediasync-quit") {
                shutdown.run()
                SwingUtilities.invokeLater { exitApplication() }
            }
        }
        if (trayAvailable) {
            MediaSyncTray(
                tooltip = if (state == StartState.WAITING_FOR_KEYCHAIN) TRAY_WAITING_TOOLTIP else TRAY_TOOLTIP,
                onShow = { visible = true },
                onQuit = quit,
            )
        }
        Window(
            onCloseRequest = {
                when (closeAction(trayAvailable)) {
                    CloseAction.HIDE_TO_TRAY -> visible = false
                    CloseAction.QUIT -> quit()
                }
            },
            visible = visible,
            title = WINDOW_TITLE,
        ) {
            DesktopRoot(graph, waitingForKeychain = state == StartState.WAITING_FOR_KEYCHAIN)
        }
    }
}

/** What the window shows: the shared navigation in the receiving role, or a wait for the keychain. */
@Composable
internal fun DesktopRoot(graph: DesktopGraph, waitingForKeychain: Boolean = false) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.viewModelFactory) {
        MediaSyncTheme {
            if (waitingForKeychain) {
                // The screens read the device identity, which is locked: show them once it is not.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(WAITING_TEXT) }
            } else {
                MediaSyncNavigation(role = DeviceRole.CONSUMER, openedLink = null, onLinkHandled = {})
            }
        }
    }
}

internal const val WAITING_TEXT = "Waiting for the keychain\u2026"

/** Listens for phones from the start, with the window open or not; waits if the keychain is still locked. */
private fun startReceiving(graph: DesktopGraph, scope: CoroutineScope): ReceiverStarter {
    val starter = ReceiverStarter(start = { graph.receiver.hold("app") }, log = ::logStartProblem)
    scope.launch {
        graph.receiver.state.first { it.listening }.let { println("MediaSync: receiving on port ${it.port}") }
    }
    scope.launch { starter.run() }
    return starter
}

private fun logStartProblem(message: String) {
    println("MediaSync: $message")
    Diagnostics.log(Area.APP, message)
}

/** Announces again over mDNS when the computer's addresses change or it wakes up. */
private fun watchNetwork(graph: DesktopGraph, scope: CoroutineScope) {
    val watcher = NetworkWatcher(
        addresses = graph.network::localAddresses,
        onChanged = { scope.launch(Dispatchers.IO) { graph.discovery.reannounce() } },
    )
    scope.launch { watcher.run() }
}
