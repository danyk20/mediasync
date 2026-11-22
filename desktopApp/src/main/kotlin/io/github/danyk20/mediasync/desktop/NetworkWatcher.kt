/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import java.net.InetAddress
import kotlinx.coroutines.delay

/**
 * Calls [onChanged] when the computer's LAN addresses are not the ones last seen, or when it was asleep (the clock
 * jumped by much more than [intervalMillis] between two looks): mDNS announcements are then stale and must be made
 * again. Polls every [intervalMillis]. [pause] and [now] are injected so tests need no real time.
 */
class NetworkWatcher(
    private val addresses: () -> List<InetAddress>,
    private val onChanged: () -> Unit,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Never returns; cancel it to stop. */
    suspend fun run() {
        var known = addresses().toSet()
        var last = now()
        while (true) {
            pause(intervalMillis)
            val current = addresses().toSet()
            val time = now()
            val slept = time - last > intervalMillis * SLEEP_FACTOR
            last = time
            if (current != known || slept) {
                known = current
                onChanged()
            }
        }
    }

    companion object {
        const val INTERVAL_MILLIS = 30_000L
        private const val SLEEP_FACTOR = 2
    }
}
