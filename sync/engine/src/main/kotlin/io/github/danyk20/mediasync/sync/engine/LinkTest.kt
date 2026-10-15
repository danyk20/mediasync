/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.LinkTestBegin
import io.github.danyk20.mediasync.sync.protocol.LinkTestEnd
import io.github.danyk20.mediasync.sync.protocol.LinkTestResult
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlin.random.Random

/** How fast filler arrived: [bytes] from the first to the last in [micros], as the consumer measured it. */
data class LinkSpeed(val bytes: Long, val micros: Long) {
    val megabytesPerSecond: Double
        get() = if (micros == 0L) 0.0 else bytes / BYTES_PER_MEGABYTE / (micros / MICROS_PER_SECOND)

    /** Two connections at once: all their bytes, in the time the slower one took. */
    operator fun plus(other: LinkSpeed) = LinkSpeed(bytes + other.bytes, maxOf(micros, other.micros))

    private companion object {
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val MICROS_PER_SECOND = 1e6
    }
}

/**
 * The link test (docs/PLAN.md §7, Diagnostics): sends incompressible filler to the consumer for [durationMillis]
 * and asks how fast it arrived. Nothing is stored. The raw speed the sync is measured against.
 */
class LinkTest(private val local: LocalDevice, private val durationMillis: Long = DEFAULT_MILLIS) {

    fun run(connection: PeerConnection): LinkSpeed {
        val channel = FrameChannel(connection)
        channel.send(Hello(PROTOCOL_VERSION, local.name, WireRole.PRODUCER, local.appVersion))
        val hello = channel.expect<Hello>()
        if (hello.protocolVersion != PROTOCOL_VERSION || hello.role != WireRole.CONSUMER) {
            channel.send(Bye(Bye.Reason.INCOMPATIBLE_VERSION))
            throw IncompatiblePeerException("Peer speaks v${hello.protocolVersion} as role ${hello.role}")
        }
        channel.send(LinkTestBegin())
        channel.expect<LinkTestBegin>() // Ready; an unpaired phone is told so here.
        val filler = Random.nextBytes(CHUNK_SIZE)
        val end = System.nanoTime() + durationMillis * NANOS_PER_MILLI
        var sent = 0L
        while (System.nanoTime() < end) {
            channel.sendData(filler, filler.size)
            sent += filler.size
        }
        channel.send(LinkTestEnd(sent))
        val result = channel.expect<LinkTestResult>()
        channel.send(Bye())
        return LinkSpeed(result.bytes, result.micros)
    }

    private companion object {
        const val DEFAULT_MILLIS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
