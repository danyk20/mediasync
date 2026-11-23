/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection

/** How to reach a phone over a direct link, for Turbo transfer (docs/PLAN.md §8.3). iOS has none. */
interface LinkRoute

/**
 * Opens connections from a sending phone to its receiving phone over the local network, and tells what the network
 * allows. Calls block: run them on an I/O thread.
 */
interface LanLinks {
    /**
     * An authenticated, encrypted connection to [address] that proves it's [expected] (its certificate's hash). [via]:
     * over this direct link instead of the network.
     *
     * @throws okio.IOException within about [connectTimeoutMillis] if it can't connect, or isn't [expected].
     */
    fun connect(
        address: HostPort,
        expected: DeviceId,
        connectTimeoutMillis: Int,
        via: LinkRoute? = null,
    ): PeerConnection

    /** Whether [host] (a literal address) is on one of the Wi-Fi or Ethernet networks this phone is on. */
    fun isOnLocalNetwork(host: String): Boolean = true

    /** Runs [session] with the Wi-Fi radio kept out of power saving (docs/PLAN.md §7). */
    fun <T> keepingAwake(session: () -> T): T = session()

    /** Whether a sync may open another connection: not on a warm phone, as more mean more radio and CPU work. */
    fun canUseMoreConnections(): Boolean = true
}
