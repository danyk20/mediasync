/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport

/** A connection that may go through the TURN relay, which counts against the relay allowance (PLAN §8.5). */
interface RelayCounter {
    /** Bytes sent and received through the relay so far; 0 while the phones are connected directly. */
    fun relayedBytes(): Long

    /** Whether the connection goes through the relay now, rather than directly between the phones. */
    fun throughRelay(): Boolean = false
}
