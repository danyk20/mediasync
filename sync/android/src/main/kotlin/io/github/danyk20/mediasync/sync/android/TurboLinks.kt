/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turbo transfer's direct links (docs/PLAN.md §8.3): Wi-Fi Aware where both phones have it, Wi-Fi Direct otherwise.
 * Whether Turbo can be used at all goes by Wi-Fi Direct, which every phone with Wi-Fi Aware has too.
 */
@Singleton
class TurboLinks internal constructor(private val direct: DirectLinks, private val aware: DirectLinks) : DirectLinks {
    @Inject
    constructor(direct: WifiDirect, aware: WifiAware) : this(direct as DirectLinks, aware as DirectLinks)

    override fun availability(): TurboAvailability = direct.availability()

    override fun awareAvailable(): Boolean = aware.awareAvailable()

    override fun open(port: Int, credentials: Pair<String, String>, kind: LinkKind): TurboAnswer =
        linksFor(kind).open(port, credentials, kind)

    // Each removes only a link of its own by that name.
    override fun close(networkName: String) {
        aware.close(networkName)
        direct.close(networkName)
    }

    override suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink? =
        linksFor(link.kind).join(link, timeoutMillis)

    override fun leave() {
        aware.leave()
        direct.leave()
    }

    private fun linksFor(kind: LinkKind) = when (kind) {
        LinkKind.WIFI_DIRECT -> direct
        LinkKind.WIFI_AWARE -> aware
    }
}
