/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.util.Lock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.darwin.dispatch_time_t

/**
 * A value handed from a Network.framework callback, or from another thread, to the blocking Kotlin thread waiting
 * for it. Only the first [offer] counts, and [offer] is safe from any thread.
 */
internal class OneShot<T : Any> {
    private val lock = Lock()
    private val semaphore = dispatch_semaphore_create(0L) ?: throw AppleLanException("dispatch_semaphore_create")
    private var value: T? = null

    /** Stores [result] and wakes the waiter. Returns false, dropping [result], when a value was already offered. */
    fun offer(result: T): Boolean {
        val first = lock.withLock {
            if (value != null) {
                false
            } else {
                value = result
                true
            }
        }
        if (first) dispatch_semaphore_signal(semaphore)
        return first
    }

    /** Waits up to [millis] for the value. `null` on timeout. The value stays available to later waits. */
    fun await(millis: Long): T? {
        if (dispatch_semaphore_wait(semaphore, deadlineAfter(millis)) != 0L) return null
        // Hand the token back, so a later await also finds the value.
        dispatch_semaphore_signal(semaphore)
        return lock.withLock { value }
    }
}

/** A dispatch time [millis] from now. */
internal fun deadlineAfter(millis: Long): dispatch_time_t = dispatch_time(DISPATCH_TIME_NOW, millis * NANOS_PER_MILLI)

private const val NANOS_PER_MILLI = 1_000_000L
