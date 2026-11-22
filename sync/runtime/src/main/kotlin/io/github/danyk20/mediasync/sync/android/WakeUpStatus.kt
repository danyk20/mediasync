/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

/** What the last check of this phone's wake-ups found, at [checkedAtMillis]; [problem]: why they don't work. */
data class WakeUpStatus(val checkedAtMillis: Long, val problem: String? = null) {
    val working: Boolean get() = problem == null
}
