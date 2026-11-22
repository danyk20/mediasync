/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** A phone has no log-in to start at: Settings shows no such row. */
@ContributesBinding(AppScope::class)
@Inject
class NoLoginStartup : LoginStartup {
    override suspend fun state(): StartAtLoginState? = null

    override suspend fun set(on: Boolean): StartAtLoginState? = null
}
