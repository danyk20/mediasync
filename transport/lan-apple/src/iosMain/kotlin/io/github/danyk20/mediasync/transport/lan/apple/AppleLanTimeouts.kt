/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

/**
 * Timeouts for the iPhone's LAN link, with the same defaults as the JVM client (`LanTimeouts` in transport:lan).
 *
 * A connect or handshake that runs out fails with an IOException. A read or write that runs out cancels the
 * connection and throws an IOException, so a stalled peer never holds a thread past its bound.
 */
data class AppleLanTimeouts(
    val connectMillis: Long = 5_000,
    val handshakeMillis: Long = 15_000,
    /** Long enough for the consumer's user to approve a pairing request. */
    val readMillis: Long = 120_000,
    val writeMillis: Long = 120_000,
)
