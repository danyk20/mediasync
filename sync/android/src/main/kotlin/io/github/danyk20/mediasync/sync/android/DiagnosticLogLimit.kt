/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the diagnostic log to the size chosen in *Settings* (docs/PLAN.md §11), from start-up on and whenever the
 * user changes it: a smaller size drops the oldest entries at once.
 */
@Singleton
class DiagnosticLogLimit @Inject constructor(
    private val settings: SettingsRepository,
    private val log: FileDiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start(): Job = scope.launch { settings.diagnosticsLogBytes.collect(log::limitTo) }
}
