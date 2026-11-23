/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.Frame
import io.github.danyk20.mediasync.sync.protocol.FrameCodec
import io.github.danyk20.mediasync.sync.protocol.FrameType
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.MessageCodec
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.HashingSink
import okio.blackholeSink

private const val PROPERTIES_FILE = "server.properties"
private const val SERVER_NAME = "interop-server"
private const val LIFETIME_MILLIS = 30L * 60 * 1_000

/** How long a silent peer keeps its connection open: longer than any stall the interop tests cause. */
private const val SILENT_HOLD_MILLIS = 60_000L

/** Bytes the drop peer writes before it closes the connection. */
private const val DROP_BYTES = 1 shl 20

/** Seed of the bytes the echo peer sends back. */
private const val MIRROR_SEED = 20_260_101L

/**
 * A MediaSync consumer on this machine, for the iOS interop tests (transport/lan-apple). Gradle starts it around
 * `:transport:lan-apple:iosSimulatorArm64Test` and stops it afterwards.
 *
 * Usage: `InteropServerKt <dir>`. It listens on three ports, all with the same identity (so the same pin), and writes
 * `<dir>/server.properties` with `port`, `silentPort`, `dropPort` and `pin` once all three accept connections. Then it
 * prints `READY port=<n> pin=<hex>`. It exits on its own after 30 minutes, so it cannot outlive a build that died
 * without stopping it. LanServer binds all interfaces: it has no option for a bind host.
 *
 * - `port` (echo): after the peer's Hello this sends its own Hello (role CONSUMER). Then it reads DATA frames until
 *   the peer's Bye, hashing them. It answers with the same number of bytes of its own (DATA frames of up to
 *   [FrameCodec.MAX_PAYLOAD_SIZE]), then two 32-byte DATA frames: the SHA-256 of what it received, and the SHA-256 of
 *   what it sent. A peer that sent nothing gets two empty digests.
 * - `silentPort`: completes the TLS handshake, then neither reads nor writes for [SILENT_HOLD_MILLIS]. A stalled peer,
 *   for the read and write timeout tests.
 * - `dropPort`: completes the handshake, writes [DROP_BYTES] raw bytes and closes the connection. A peer that closes
 *   mid-stream.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: InteropServer <dir>" }
    val dir = File(args[0]).apply { mkdirs() }
    thread(isDaemon = true, name = "interop-lifetime") {
        Thread.sleep(LIFETIME_MILLIS)
        println("InteropServer reached its 30 minute lifetime and exits")
        exitProcess(0)
    }
    val identity = testIdentity(SERVER_NAME)
    LanServer(identity, PeerTrust { true }).use { echo ->
        LanServer(identity, PeerTrust { true }).use { silent ->
            LanServer(identity, PeerTrust { true }).use { drop ->
                writeProperties(
                    dir,
                    mapOf(
                        "port" to echo.port.toString(),
                        "silentPort" to silent.port.toString(),
                        "dropPort" to drop.port.toString(),
                        "pin" to identity.id.hex,
                    ),
                )
                println("READY port=${echo.port} pin=${identity.id.hex}")
                thread(isDaemon = true, name = "interop-silent") { acceptLoop(silent, ::holdSilently) }
                thread(isDaemon = true, name = "interop-drop") { acceptLoop(drop, ::dropAfterData) }
                acceptLoop(echo, ::serve)
            }
        }
    }
}

/** Accepts forever, handling each peer on its own thread. */
private fun acceptLoop(server: LanServer, handle: (LanServer.Pending) -> Unit) {
    while (true) {
        val pending = server.accept()
        thread(isDaemon = true, name = "interop-peer") { handle(pending) }
    }
}

private fun serve(pending: LanServer.Pending) {
    try {
        val connection = pending.handshake()
        try {
            exchange(connection)
        } finally {
            connection.close()
        }
    } catch (e: IOException) {
        println("peer failed: ${e.message}")
    } catch (e: IllegalStateException) {
        println("peer sent an unexpected message: ${e.message}")
    }
}

private fun holdSilently(pending: LanServer.Pending) {
    try {
        val connection = pending.handshake()
        Thread.sleep(SILENT_HOLD_MILLIS)
        connection.close()
    } catch (e: IOException) {
        println("silent peer failed: ${e.message}")
    }
}

private fun dropAfterData(pending: LanServer.Pending) {
    try {
        val connection = pending.handshake()
        connection.sink.write(ByteArray(DROP_BYTES))
        connection.sink.flush()
        connection.close()
    } catch (e: IOException) {
        println("drop peer failed: ${e.message}")
    }
}

private fun exchange(connection: PeerConnection) {
    val first = FrameCodec.read(connection.source)
    val hello = first?.let { MessageCodec.decode(it) } as? Hello ?: error("expected Hello first")
    check(hello.role == WireRole.PRODUCER) { "expected a producer Hello, got role ${hello.role}" }
    FrameCodec.write(MessageCodec.encode(Hello(PROTOCOL_VERSION, SERVER_NAME, WireRole.CONSUMER)), connection.sink)
    connection.sink.flush()

    val received = Digest()
    var receivedBytes = 0L
    var frame = FrameCodec.read(connection.source)
    while (frame != null && frame.type != FrameType.BYE) {
        if (frame.type == FrameType.DATA) {
            received.update(frame.payload)
            receivedBytes += frame.payload.size
        }
        frame = FrameCodec.read(connection.source)
    }
    // The peer closed without its Bye: there is nobody to answer.
    if (frame == null) return
    echoBack(connection.sink, receivedBytes, received.finish())
}

/** Sends [total] pseudo-random bytes in DATA frames, then the two digests (see the class comment). */
private fun echoBack(sink: BufferedSink, total: Long, receivedDigest: ByteString) {
    val random = Random(MIRROR_SEED)
    val sent = Digest()
    var remaining = total
    while (remaining > 0) {
        val size = minOf(remaining, FrameCodec.MAX_PAYLOAD_SIZE.toLong()).toInt()
        val payload = ByteArray(size).also { random.nextBytes(it) }
        FrameCodec.write(Frame(FrameType.DATA, payload.toByteString()), sink)
        sent.update(payload)
        remaining -= size
    }
    FrameCodec.write(Frame(FrameType.DATA, receivedDigest), sink)
    FrameCodec.write(Frame(FrameType.DATA, sent.finish()), sink)
    sink.flush()
}

/** An incremental SHA-256 of the bytes given to [update]. */
private class Digest {
    private val hashing = HashingSink.sha256(blackholeSink())

    fun update(bytes: ByteArray) {
        hashing.write(Buffer().write(bytes), bytes.size.toLong())
    }

    fun update(bytes: ByteString) {
        hashing.write(Buffer().write(bytes), bytes.size.toLong())
    }

    fun finish(): ByteString = hashing.hash
}

/** Written under a temporary name and moved into place, so a reader never sees half a file. */
private fun writeProperties(dir: File, entries: Map<String, String>) {
    val temporary = File(dir, "$PROPERTIES_FILE.tmp")
    temporary.outputStream().use { out ->
        Properties().apply { entries.forEach { (key, value) -> setProperty(key, value) } }
            .store(out, "MediaSync interop server")
    }
    Files.move(temporary.toPath(), File(dir, PROPERTIES_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
}
