/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import javax.inject.Inject

/**
 * How this phone reaches other phones: its local network, mDNS on it, direct links, and the internet
 * (docs/PLAN.md §8).
 */
class Connectivity @Inject constructor(
    val addresses: AddressProvider,
    val sockets: LanSockets,
    val discovery: ServiceDiscovery,
    val directLinks: DirectLinks,
    val internet: InternetLinks = InternetLinks.None,
)
