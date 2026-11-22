/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.feature.diagnostics.LoginStartup
import io.github.danyk20.mediasync.feature.diagnostics.StartAtLoginState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The argument a login item adds: start hidden in the tray instead of showing the window. */
const val BACKGROUND_ARGUMENT = "--background"

/** Whether the window stays hidden at start: asked to (`--background`) and there is a tray to open it from. */
fun startsHidden(arguments: Array<String>, trayAvailable: Boolean): Boolean =
    BACKGROUND_ARGUMENT in arguments && trayAvailable

/**
 * The program that starts this app: the installer's launcher, which sets `jpackage.app-path`. A copy started from
 * Gradle or `java -jar` has none (`null`): there is nothing installed to start at login.
 */
fun installedLauncher(properties: (String) -> String? = System::getProperty): Path? =
    properties("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf(Files::isRegularFile)

/** The program that starts this app, or `null` if there is none. */
fun interface Launcher {
    fun path(): Path?
}

/**
 * *Start at login* on a computer. Off until the first sending phone pairs with it; then, once, it turns on
 * ([watchProducers]). A file in the data folder remembers that decision, so turning it off sticks.
 */
@ContributesBinding(AppScope::class)
@Inject
class DesktopStartAtLogin(private val item: LoginItem, private val launcher: Launcher, private val paths: AppPaths) :
    LoginStartup {

    override suspend fun state(): StartAtLoginState = withContext(Dispatchers.IO) { current() }

    override suspend fun set(on: Boolean): StartAtLoginState = withContext(Dispatchers.IO) {
        val program = launcher.path()
        if (on && program != null) {
            item.enable(command(program))
        } else if (!on) {
            item.disable()
        }
        current()
    }

    /** Watches how many sending phones are paired, as [paired] emits them. */
    suspend fun watchProducers(paired: Flow<Int>) {
        var before: Int? = null
        paired.collect { count ->
            if (before == 0 && count >= 1) firstPairing()
            before = count
        }
    }

    /**
     * Each start, off the UI thread: keeps the entry pointing at this copy's launcher, in case the app moved. Only
     * once the setting is decided and on. Does nothing for a copy that is not installed or has its own data folder.
     */
    suspend fun refreshEntry() = withContext(Dispatchers.IO) {
        val program = launcher.path() ?: return@withContext
        if (!paths.isolated && Files.exists(marker()) && item.isEnabled()) item.enable(command(program))
    }

    /**
     * The first time a sending phone pairs with an installed copy: turns the setting on and records the decision.
     * Later pairings change nothing, so a user who turned it off is never switched back on.
     */
    private suspend fun firstPairing() = withContext(Dispatchers.IO) {
        val program = launcher.path() ?: return@withContext
        if (paths.isolated || Files.exists(marker())) return@withContext
        Files.createDirectories(marker().parent)
        Files.createFile(marker())
        item.enable(command(program))
    }

    private fun marker(): Path = paths.root.resolve(DECIDED)

    private fun current() = StartAtLoginState(on = item.isEnabled(), canChange = launcher.path() != null)

    private fun command(program: Path) = listOf(program.toString(), BACKGROUND_ARGUMENT)

    private companion object {
        const val DECIDED = "start-at-login-decided"
    }
}
