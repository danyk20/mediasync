/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.sync.protocol.FrameCodec
import kotlin.test.Test

private const val ONE_MIB = 1 shl 20
private const val SIXTY_FOUR_MIB = 64L * ONE_MIB

/** Bulk transfers against the echo peer: the bytes must arrive intact, and the throughput is logged, not asserted. */
class BulkTransferTest {
    @Test
    fun aBulkRoundTripOfSixtyFourMebibytesMatchesOnBothSides() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.port,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(),
            )
            try {
                helloExchange(connection)
                val outcome = bulkRoundTrip(connection, SIXTY_FOUR_MIB, ONE_MIB)
                assertBulkMatches(outcome)
                recordLine(
                    "THROUGHPUT 64 MiB each way over 1 MiB frames: upload ${roundedMbps(outcome.uploadMbps)} MB/s, " +
                        "download ${roundedMbps(outcome.downloadMbps)} MB/s",
                )
            } finally {
                connection.close()
            }
        }
        recordPass("aBulkRoundTripOfSixtyFourMebibytesMatchesOnBothSides")
    }

    @Test
    fun aFrameAtTheProtocolSizeLimitRoundTrips() {
        val serverDir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(serverDir)
        inTempDirectory { identityDir ->
            val connection = NwLanClient.connect(
                "127.0.0.1",
                server.port,
                loadedIdentity(identityDir),
                server.pin,
                AppleLanTimeouts(),
            )
            try {
                helloExchange(connection)
                // One frame of exactly MAX_PAYLOAD_SIZE in each direction: the largest frame the protocol allows.
                val limit = FrameCodec.MAX_PAYLOAD_SIZE
                assertBulkMatches(bulkRoundTrip(connection, limit.toLong(), limit))
            } finally {
                connection.close()
            }
        }
        recordPass("aFrameAtTheProtocolSizeLimitRoundTrips")
    }
}
