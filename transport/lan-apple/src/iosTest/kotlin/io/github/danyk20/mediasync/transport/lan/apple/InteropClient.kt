/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.Frame
import io.github.danyk20.mediasync.sync.protocol.FrameCodec
import io.github.danyk20.mediasync.sync.protocol.FrameType
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.MessageCodec
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import okio.ByteString
import okio.ByteString.Companion.toByteString
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

private const val CLIENT_SEED = 42L

/** The next frame, or a failure when the peer closed before it. */
internal fun requireFrame(frame: Frame?): Frame = checkNotNull(frame) { "The server closed the connection early" }

/** Producer Hello, then the consumer's Hello back. The first step of every interop case. */
internal fun helloExchange(connection: PeerConnection) {
    FrameCodec.write(MessageCodec.encode(Hello(PROTOCOL_VERSION, "ios-interop", WireRole.PRODUCER)), connection.sink)
    connection.sink.flush()
    val reply = assertIs<Hello>(MessageCodec.decode(requireFrame(FrameCodec.read(connection.source))))
    assertEquals(PROTOCOL_VERSION, reply.protocolVersion)
    assertEquals(WireRole.CONSUMER, reply.role)
}

/** An incremental SHA-256 of the bytes given to [update], by CommonCrypto (as the production hashing does). */
@OptIn(ExperimentalForeignApi::class)
internal class Digest {
    private val context = ByteArray(sizeOf<CC_SHA256_CTX>().toInt())

    init {
        context.usePinned { CC_SHA256_Init(it.addressOf(0).reinterpret()) }
    }

    fun update(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        context.usePinned { ctx ->
            bytes.usePinned { data ->
                CC_SHA256_Update(ctx.addressOf(0).reinterpret(), data.addressOf(0), bytes.size.convert())
            }
        }
    }

    fun update(bytes: ByteString) = update(bytes.toByteArray())

    fun finish(): ByteString {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        context.usePinned { ctx ->
            out.usePinned { md -> CC_SHA256_Final(md.addressOf(0).reinterpret(), ctx.addressOf(0).reinterpret()) }
        }
        return out.toByteString()
    }
}

/**
 * What one bulk round trip measured: the digests on each side, and the throughput in MB/s (10^6 bytes per second)
 * for each direction.
 */
internal class BulkOutcome(
    val sentDigest: ByteString,
    val receivedDigest: ByteString,
    val serverReceivedDigest: ByteString,
    val serverSentDigest: ByteString,
    val uploadMbps: Double,
    val downloadMbps: Double,
)

/**
 * Sends [totalBytes] of pseudo-random data to the echo peer in DATA frames of [frameBytes] each, then its Bye. Then it
 * reads the peer's [totalBytes] back and its two digests. Both directions are hashed here and on the peer.
 *
 * Upload completes before download starts, so neither side can block the other: the peer reads everything before it
 * writes anything.
 */
internal fun bulkRoundTrip(connection: PeerConnection, totalBytes: Long, frameBytes: Int): BulkOutcome {
    val random = Random(CLIENT_SEED)
    val sent = Digest()
    // One buffer serves every frame: the timing measures the transport, not the generator. Each frame is still hashed.
    val frame = ByteArray(minOf(frameBytes.toLong(), totalBytes).toInt()).also { random.nextBytes(it) }
    val uploadStart = TimeSource.Monotonic.markNow()
    var written = 0L
    while (written < totalBytes) {
        val size = minOf(frameBytes.toLong(), totalBytes - written).toInt()
        val payload = if (size == frame.size) frame else frame.copyOf(size)
        FrameCodec.write(Frame(FrameType.DATA, payload.toByteString()), connection.sink)
        sent.update(payload)
        written += size
    }
    FrameCodec.write(MessageCodec.encode(Bye()), connection.sink)
    connection.sink.flush()
    val upload = uploadStart.elapsedNow()

    val downloadStart = TimeSource.Monotonic.markNow()
    val received = Digest()
    var read = 0L
    while (read < totalBytes) {
        val frame = requireFrame(FrameCodec.read(connection.source))
        check(frame.type == FrameType.DATA) { "Expected DATA, got ${frame.type.code}" }
        received.update(frame.payload)
        read += frame.payload.size
    }
    check(read == totalBytes) { "The peer sent $read bytes, expected $totalBytes" }
    val serverReceived = requireFrame(FrameCodec.read(connection.source)).payload
    val serverSent = requireFrame(FrameCodec.read(connection.source)).payload
    val download = downloadStart.elapsedNow()

    return BulkOutcome(
        sentDigest = sent.finish(),
        receivedDigest = received.finish(),
        serverReceivedDigest = serverReceived,
        serverSentDigest = serverSent,
        uploadMbps = megabytesPerSecond(totalBytes, upload),
        downloadMbps = megabytesPerSecond(totalBytes, download),
    )
}

/** Both directions carry the bytes the other side sent: the digests must match on each side. */
internal fun assertBulkMatches(outcome: BulkOutcome) {
    assertEquals(outcome.sentDigest, outcome.serverReceivedDigest, "The peer received other bytes than were sent")
    assertEquals(outcome.serverSentDigest, outcome.receivedDigest, "The client received other bytes than were sent")
}

/** Rounded to one decimal, for the log. */
internal fun roundedMbps(value: Double): String {
    val tenths = (value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

private fun megabytesPerSecond(bytes: Long, elapsed: Duration): Double =
    bytes / MEGABYTE / elapsed.toDouble(DurationUnit.SECONDS)

private const val MEGABYTE = 1_000_000.0

/** An identity saved by one provider in [directory], then loaded by a new one: the relaunch path. */
internal fun loadedIdentity(directory: String): AppleIdentity {
    AppleIdentityProvider(FileIdentityStore(directory), NO_LOG).identity()
    return AppleIdentityProvider(FileIdentityStore(directory), NO_LOG).identity()
}
