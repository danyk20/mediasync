/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.PairRequest
import io.github.danyk20.mediasync.sync.protocol.PairResult
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import okio.ByteString

/** The outcome of pairing: on success, the consumer to remember. */
data class PairingOutcome(
    val decision: PairingDecision,
    val consumer: PairedPeer?,
    /** The schedule the consumer asks its producers to follow; `null` if it didn't say. */
    val consumerSchedule: SyncSchedule? = null,
)

/**
 * Producer side of pairing (docs/PLAN.md §11).
 *
 * The connection must already be pinned to the certificate hash from the consumer's QR code; the
 * [token] from the same QR code proves to the consumer that this producer scanned it.
 */
class PairingClient(private val local: LocalDevice) {

    fun pair(connection: PeerConnection, token: ByteString): PairingOutcome {
        val channel = FrameChannel(connection)
        channel.send(Hello(PROTOCOL_VERSION, local.name, WireRole.PRODUCER, local.appVersion))
        val hello = channel.expect<Hello>()
        if (hello.protocolVersion != PROTOCOL_VERSION || hello.role != WireRole.CONSUMER) {
            channel.send(Bye(Bye.Reason.INCOMPATIBLE_VERSION))
            throw IncompatiblePeerException("Peer speaks v${hello.protocolVersion} as role ${hello.role}")
        }
        channel.send(PairRequest(token))
        val result = channel.expect<PairResult>()
        channel.send(Bye())
        return if (result.accepted) {
            PairingOutcome(
                PairingDecision.ACCEPTED,
                PairedPeer(connection.peerId, hello.deviceName, DeviceRole.CONSUMER),
                SyncSchedule.decode(hello.schedule),
            )
        } else {
            PairingOutcome(result.reason.toPairingDecision(), consumer = null)
        }
    }
}
