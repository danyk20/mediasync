/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.TestFile
import io.github.danyk20.mediasync.sync.engine.TestFileHeld
import io.github.danyk20.mediasync.sync.engine.TestFileOutcome

/**
 * The test file, as both phones' *Diagnostics* show it (docs/PLAN.md §7, *Test file*): on a sending phone, the one
 * [waiting] to be sent and, while it keeps waiting, the [problem] why; on both, the [last] one checked, at
 * [lastAtMillis].
 */
data class TestFileState(
    val waiting: TestFile? = null,
    val problem: TestFileHeld? = null,
    val last: TestFileOutcome? = null,
    val lastAtMillis: Long? = null,
)
