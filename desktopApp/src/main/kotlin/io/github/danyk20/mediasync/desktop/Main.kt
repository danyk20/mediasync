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
import androidx.compose.runtime.key
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
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.feature.diagnostics.LocalResetMediaSync
import io.github.danyk20.mediasync.feature.onboarding.LocalIsComputer
import io.github.danyk20.mediasync.navigation.MediaSyncNavigation
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val WINDOW_TITLE = "MediaSync"

/** The desktop app: sends or receives, as the user chose (docs/PLAN.md §9, M8 and M9). */
fun main(arguments: Array<String>) {
    val graph = createGraphFactory<DesktopGraph.Factory>().create(AppPaths.resolve())
    Diagnostics.install(graph.diagnosticLog)
    Diagnostics.logCrashes(graph.diagnosticLog::flush)
    println("MediaSync ${AppVersion.current}: data in ${graph.paths.root}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Starts the part of the stored role, now or once the user chooses one.
    scope.launch { graph.roles.run(scope) }
    scope.launch(Dispatchers.IO) { runCatching { graph.startAtLogin.refreshEntry() } }
    val shutdown = AppShutdown(graph.shutdownSteps(scope))
    application {
        val trayAvailable = remember { trayAvailable() }
        var visible by remember { mutableStateOf(!startsHidden(arguments, trayAvailable)) }
        // Whichever part runs reports whether it waits for the keychain.
        val state by remember {
            combine(graph.receiving.state, graph.sending.state) { receiving, sending -> receiving ?: sending }
        }.collectAsState(initial = null)
        val role by remember { graph.settings.role }.collectAsState(initial = null)
        val quit: () -> Unit = {
            visible = false // Gone at once; the shutdown runs behind it, off the UI thread.
            thread(name = "mediasync-quit") {
                shutdown.run()
                SwingUtilities.invokeLater { exitApplication() }
            }
        }
        if (trayAvailable) {
            MediaSyncTray(
                tooltip = when {
                    state == StartState.WAITING_FOR_KEYCHAIN -> TRAY_WAITING_TOOLTIP
                    role == DeviceRole.PRODUCER -> TRAY_SENDING_TOOLTIP
                    else -> TRAY_TOOLTIP
                },
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
            DesktopRoot(
                graph,
                waitingForKeychain = state == StartState.WAITING_FOR_KEYCHAIN,
                // Forgets the role and the pairings, then closes: the next start asks again (as on a phone).
                onReset = {
                    scope.launch {
                        graph.reset.reset()
                        SwingUtilities.invokeLater(quit)
                    }
                },
            )
        }
    }
}

/** The stored role, or that it is not read yet. */
private sealed interface RoleState {
    data object Loading : RoleState

    data class Known(val role: DeviceRole?) : RoleState
}

/**
 * What the window shows: the shared navigation for the stored role (the role choice while there is none), or a wait for
 * the keychain. [onReset] is what *Reset MediaSync* does.
 */
@Composable
internal fun DesktopRoot(graph: DesktopGraph, waitingForKeychain: Boolean = false, onReset: () -> Unit = {}) {
    CompositionLocalProvider(
        LocalMetroViewModelFactory provides graph.viewModelFactory,
        LocalIsComputer provides true,
        LocalResetMediaSync provides onReset,
    ) {
        MediaSyncTheme {
            if (waitingForKeychain) {
                // The screens read the device identity, which is locked: show them once it is not.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(WAITING_TEXT) }
            } else {
                val roleState by remember(graph) {
                    graph.settings.role.map<DeviceRole?, RoleState> { RoleState.Known(it) }
                }.collectAsState(initial = RoleState.Loading)
                when (val shown = roleState) {
                    RoleState.Loading -> Box(Modifier.fillMaxSize())

                    // A new role starts a fresh back stack, as on a phone.
                    is RoleState.Known -> key(shown.role) {
                        MediaSyncNavigation(role = shown.role, openedLink = null, onLinkHandled = {})
                    }
                }
            }
        }
    }
}

internal const val WAITING_TEXT = "Waiting for the keychain\u2026"

internal fun logStartProblem(message: String) {
    println("MediaSync: $message")
    Diagnostics.log(Area.APP, message)
}
