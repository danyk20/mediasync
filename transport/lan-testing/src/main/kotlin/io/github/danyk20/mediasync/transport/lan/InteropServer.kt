/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.sync.protocol.Bye
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
import kotlin.system.exitProcess

private const val PROPERTIES_FILE = "server.properties"
private const val SERVER_NAME = "interop-server"
private const val LIFETIME_MILLIS = 30L * 60 * 1_000

/**
 * A MediaSync consumer on this machine, for the iOS interop tests (transport/lan-apple). Gradle starts it around
 * `:transport:lan-apple:iosSimulatorArm64Test` and stops it afterwards.
 *
 * Usage: `InteropServerKt <dir>`. Once it accepts connections it writes `<dir>/server.properties` with `port` and
 * `pin` (the SHA-256 of its certificate, as a DeviceId), then prints `READY port=<n> pin=<hex>`. For each peer:
 * the peer's Hello comes first, then this server answers with its own Hello (role CONSUMER), then it reads until the
 * peer sends Bye or closes the connection. It exits on its own after 30 minutes, so it cannot outlive a build that
 * died without stopping it. LanServer binds all interfaces: it has no option for a bind host, so this is not limited
 * to loopback.
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
    LanServer(identity, PeerTrust { true }).use { server ->
        writeProperties(dir, port = server.port, pin = identity.id.hex)
        println("READY port=${server.port} pin=${identity.id.hex}")
        while (true) {
            val pending = server.accept()
            thread(isDaemon = true, name = "interop-peer") { serve(pending) }
        }
    }
}

private fun serve(pending: LanServer.Pending) {
    try {
        val connection = pending.handshake()
        try {
            exchangeHello(connection)
        } finally {
            connection.close()
        }
    } catch (e: IOException) {
        println("peer failed: ${e.message}")
    } catch (e: IllegalStateException) {
        println("peer sent an unexpected message: ${e.message}")
    }
}

private fun exchangeHello(connection: PeerConnection) {
    val first = FrameCodec.read(connection.source)
    val hello = first?.let { MessageCodec.decode(it) } as? Hello ?: error("expected Hello first")
    check(hello.role == WireRole.PRODUCER) { "expected a producer Hello, got role ${hello.role}" }
    FrameCodec.write(MessageCodec.encode(Hello(PROTOCOL_VERSION, SERVER_NAME, WireRole.CONSUMER)), connection.sink)
    connection.sink.flush()
    var frame = FrameCodec.read(connection.source)
    while (frame != null && frame.type != FrameType.BYE) {
        frame = FrameCodec.read(connection.source)
    }
}

/** Written under a temporary name and moved into place, so a reader never sees half a file. */
private fun writeProperties(dir: File, port: Int, pin: String) {
    val temporary = File(dir, "$PROPERTIES_FILE.tmp")
    temporary.outputStream().use { out ->
        Properties().apply {
            setProperty("port", port.toString())
            setProperty("pin", pin)
        }.store(out, "MediaSync interop server")
    }
    Files.move(temporary.toPath(), File(dir, PROPERTIES_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
}
