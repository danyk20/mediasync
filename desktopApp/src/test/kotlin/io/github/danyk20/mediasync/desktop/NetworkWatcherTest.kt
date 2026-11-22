/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.kotest.matchers.shouldBe
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class NetworkWatcherTest {

    private fun ip(text: String) = InetAddress.getByName(text)

    /** Runs the watcher for [rounds] polls: [pause] advances the fake clock by [step] and cancels after the last. */
    private suspend fun watch(
        rounds: Int,
        addresses: (Int) -> List<InetAddress>,
        step: (Int) -> Long = { 30_000L },
    ): Int {
        var round = 0
        var time = 0L
        var changes = 0
        val watcher = NetworkWatcher(
            addresses = { addresses(round) },
            onChanged = { changes++ },
            now = { time },
            pause = {
                if (round == rounds) throw CancellationException("done")
                time += step(round)
                round++
            },
        )
        try {
            watcher.run()
        } catch (_: CancellationException) {
            // The fake pause ends the loop.
        }
        return changes
    }

    @Test
    fun `an address change announces once`() = runTest {
        val changes = watch(rounds = 4, addresses = {
            if (it <
                2
            ) {
                listOf(ip("192.168.1.20"))
            } else {
                listOf(ip("10.0.0.5"))
            }
        })

        changes shouldBe 1
    }

    @Test
    fun `no change announces nothing, whatever the order of the addresses`() = runTest {
        val a = ip("192.168.1.20")
        val b = ip("10.0.0.5")
        val changes = watch(rounds = 4, addresses = { if (it % 2 == 0) listOf(a, b) else listOf(b, a) })

        changes shouldBe 0
    }

    @Test
    fun `waking from sleep announces even with the same addresses`() = runTest {
        val changes = watch(rounds = 3, addresses = { listOf(ip("192.168.1.20")) }, step = {
            if (it ==
                1
            ) {
                600_000L
            } else {
                30_000L
            }
        })

        changes shouldBe 1
    }
}
