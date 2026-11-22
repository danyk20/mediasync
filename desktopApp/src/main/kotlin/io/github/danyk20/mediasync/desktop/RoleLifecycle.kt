/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.model.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What runs while the computer has one role. */
interface RolePart {
    /** Starts what the role needs, in [scope]; returns at once. */
    fun start(scope: CoroutineScope)

    /** Stops it, waiting for what is in progress; safe to call when it never started. */
    suspend fun stop()
}

/**
 * Runs the [RolePart] of the computer's role: the receiving part for [DeviceRole.CONSUMER], the sending part for
 * [DeviceRole.PRODUCER], nothing while the role is not chosen. The role is chosen once; *Reset MediaSync* clears it
 * and closes the app (as on a phone), so a part that was stopped is never started again in the same run.
 */
class RoleLifecycle(
    private val roles: Flow<DeviceRole?>,
    private val parts: Map<DeviceRole, RolePart>,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var running: Pair<DeviceRole, RolePart>? = null
    private val used = mutableSetOf<DeviceRole>()
    private var closed = false

    /** The role whose part runs now, or `null`. */
    val active: DeviceRole? get() = running?.first

    /** Follows the stored role until cancelled; the parts run in [scope]. */
    suspend fun run(scope: CoroutineScope) {
        roles.distinctUntilChanged().collect { apply(it, scope) }
    }

    internal suspend fun apply(role: DeviceRole?, scope: CoroutineScope) = lock.withLock {
        if (closed || role == running?.first) return@withLock
        running?.let { (_, part) -> part.stop() }
        running = null
        val part = role?.let(parts::get) ?: return@withLock
        if (role in used) {
            log("The $role part was stopped; restart MediaSync to run it again")
            return@withLock
        }
        used += role
        part.start(scope)
        running = role to part
    }

    /** For quitting: stops the running part, and nothing starts afterwards. Safe when nothing ever started. */
    suspend fun stopAll() = lock.withLock {
        closed = true
        running?.let { (_, part) -> part.stop() }
        running = null
    }
}
