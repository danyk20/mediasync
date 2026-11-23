/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * A value handed from a Network.framework callback to the blocking Kotlin thread waiting for it.
 *
 * Only the first [offer] counts. Callbacks for one connection run on its serial dispatch queue, so [offer] calls do
 * not race with each other; the semaphore orders the write before the [await] read.
 */
internal class OneShot<T : Any> {
    private val semaphore = dispatch_semaphore_create(0L) ?: throw AppleLanException("dispatch_semaphore_create")
    private var value: T? = null
    private var offered = false

    fun offer(result: T) {
        if (offered) return
        offered = true
        value = result
        dispatch_semaphore_signal(semaphore)
    }

    /** Waits up to [millis] for the value. `null` on timeout. */
    fun await(millis: Long): T? {
        val deadline = dispatch_time(DISPATCH_TIME_NOW, millis * NANOS_PER_MILLI)
        return if (dispatch_semaphore_wait(semaphore, deadline) == 0L) value else null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
