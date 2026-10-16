/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Turbo's direct links: Wi-Fi Aware where both phones have it, Wi-Fi Direct otherwise (docs/PLAN.md §8.3). */
class TurboLinksTest {

    private val direct = Links(TurboAvailability.AVAILABLE, aware = false)
    private val aware = Links(TurboAvailability.UNSUPPORTED, aware = true)
    private val links = TurboLinks(direct, aware)

    @Test
    fun eachKindOfLinkGoesToItsOwnRadio() = runBlocking<Unit> {
        links.open(1, "DIRECT-ab-MediaSync" to "pass", LinkKind.WIFI_DIRECT)
        links.open(1, "MediaSync-abc" to "pass", LinkKind.WIFI_AWARE)
        links.join(DirectLink("MediaSync-abc", "pass", "", 1, LinkKind.WIFI_AWARE), timeoutMillis = 1)

        direct.calls shouldBe listOf("open DIRECT-ab-MediaSync")
        aware.calls shouldBe listOf("open MediaSync-abc", "join MediaSync-abc")
    }

    @Test
    fun turboIsThereWithWifiDirectAndAwareIsAskedOfItsOwn() {
        links.availability() shouldBe TurboAvailability.AVAILABLE
        links.awareAvailable() shouldBe true
    }

    @Test
    fun closingAndLeavingReachBoth() {
        links.close("MediaSync-abc")
        links.leave()

        direct.calls shouldBe listOf("close MediaSync-abc", "leave")
        aware.calls shouldBe listOf("close MediaSync-abc", "leave")
    }

    private class Links(private val availability: TurboAvailability, private val aware: Boolean) : DirectLinks {
        val calls = mutableListOf<String>()

        override fun availability() = availability

        override fun awareAvailable() = aware

        override fun open(port: Int, credentials: Pair<String, String>, kind: LinkKind): TurboAnswer {
            calls += "open ${credentials.first}"
            return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        }

        override fun close(networkName: String) {
            calls += "close $networkName"
        }

        override suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink {
            calls += "join ${link.networkName}"
            return JoinedLink(InetAddress.getLoopbackAddress())
        }

        override fun leave() {
            calls += "leave"
        }
    }
}
