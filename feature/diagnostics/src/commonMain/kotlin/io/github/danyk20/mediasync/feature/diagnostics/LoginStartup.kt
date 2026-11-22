/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

/**
 * Whether MediaSync starts when the user logs in to a computer. [on]: it does; [canChange]: this copy can be
 * registered (a copy run from Gradle, not installed, has nothing to start).
 */
data class StartAtLoginState(val on: Boolean, val canChange: Boolean)

/** Starting at login, for a device that has it: a computer. A phone has none and shows no setting. */
interface LoginStartup {
    /** The setting as it stands; `null` if this device has no such setting. */
    suspend fun state(): StartAtLoginState?

    /** Turns it [on] or off, and returns how it stands afterwards. */
    suspend fun set(on: Boolean): StartAtLoginState?
}
