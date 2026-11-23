/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.transport.lan.IdentityProvider

/**
 * How this phone reaches other phones: its local network, mDNS on it, direct links, and the internet
 * (docs/PLAN.md §8).
 */
class Connectivity(
    val addresses: AddressProvider,
    val sockets: LanSockets,
    val discovery: ServiceDiscovery,
    val directLinks: DirectLinks,
    val internet: InternetLinks = InternetLinks.None,
) {
    /** As a sending phone: these links, over this phone's network and with its [identity]. */
    fun forSending(identity: IdentityProvider): SendLinks =
        SendLinks(SocketLanLinks(sockets, identity), discovery, directLinks, internet)
}
