/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import kotlin.time.Clock
import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

/**
 * Nanoseconds on a monotonic clock, from an arbitrary zero in this process: for measuring and comparing durations.
 * Replaces System.nanoTime, which Kotlin/Native doesn't offer in common code.
 */
fun monotonicNanos(): Long = origin.elapsedNow().inWholeNanoseconds

/** Wall-clock milliseconds since the epoch; for timestamps that are stored or shown, never for measuring durations. */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
