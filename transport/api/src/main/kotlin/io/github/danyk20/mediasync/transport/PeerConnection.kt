/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport

import io.github.danyk20.mediasync.core.model.DeviceId
import java.io.Closeable
import okio.BufferedSink
import okio.BufferedSource

/**
 * An ordered, reliable, encrypted byte stream to a peer whose identity has already been authenticated.
 *
 * Every transport (local Wi-Fi, Turbo, internet) produces these. The sync engine never knows which one
 * it is using (docs/PLAN.md §8.1).
 */
interface PeerConnection : Closeable {
    /** The authenticated remote device: the hash of the certificate it presented. */
    val peerId: DeviceId

    val source: BufferedSource

    val sink: BufferedSink

    /** Where the peer was reached, e.g. `192.168.1.20:41234`. For diagnostics and reconnecting. */
    val remoteAddress: String
}
