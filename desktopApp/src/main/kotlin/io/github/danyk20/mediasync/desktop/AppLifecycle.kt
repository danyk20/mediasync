/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** What closing the window does. */
enum class CloseAction { HIDE_TO_TRAY, QUIT }

/** Hide to the tray only when there is a tray to come back from: never leave an invisible app with no way back. */
fun closeAction(trayAvailable: Boolean): CloseAction = if (trayAvailable) CloseAction.HIDE_TO_TRAY else CloseAction.QUIT

/**
 * Stops everything the app runs, in order, on a background thread. Waits at most [run]'s limit, so a step that hangs
 * cannot keep the app from quitting. A step that fails does not keep the others from running.
 */
class AppShutdown(private val steps: List<() -> Unit>) {
    fun run(limitMillis: Long = LIMIT_MILLIS) {
        val worker = thread(name = "mediasync-shutdown", isDaemon = true) {
            steps.forEach(::runStep)
        }
        worker.join(limitMillis)
    }

    @Suppress("TooGenericExceptionCaught") // Quitting goes on whatever one step throws.
    private fun runStep(step: () -> Unit) {
        try {
            step()
        } catch (_: Exception) {
            // Nothing to do about it on the way out.
        }
    }

    companion object {
        /** The most quitting may take before the app exits anyway. */
        const val LIMIT_MILLIS = 10_000L
    }
}

/**
 * What quitting does, in order, whichever role runs (or none yet): the role's part stops first (a copy finishes, the
 * servers close; nothing happens for a part that never started), then [cancelScope], then [closeDatabase].
 */
fun shutdownSteps(roles: RoleLifecycle, cancelScope: () -> Unit, closeDatabase: () -> Unit): List<() -> Unit> =
    listOf({ runBlocking { roles.stopAll() } }, cancelScope, closeDatabase)
