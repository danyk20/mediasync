/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

/**
 * Nanoseconds on a monotonic clock, from an arbitrary zero in this process: for measuring and comparing durations.
 * Replaces System.nanoTime, which Kotlin/Native doesn't offer in common code.
 */
internal fun monotonicNanos(): Long = origin.elapsedNow().inWholeNanoseconds
