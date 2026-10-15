/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source

/** Socket timeouts. A stalled peer fails the session instead of hanging it. */
data class LanTimeouts(
    val connectMillis: Int = 5_000,
    /** Long enough for the consumer's user to approve a pairing request. */
    val readMillis: Int = 120_000,
    /**
     * For the TLS handshake, which needs nobody's approval: something that accepted the connection but doesn't
     * answer as MediaSync (a stale address now taken by another device) fails it soon, not after [readMillis].
     */
    val handshakeMillis: Int = 15_000,
)

/** Large buffers keep Wi-Fi busy during bulk transfers (docs/PLAN.md §7). */
private const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024

/**
 * Accepts encrypted connections from other devices on the local network (the consumer side).
 *
 * @param trust which devices may connect. During pairing it admits unknown devices; the sync engine
 *   then requires a valid pairing token before anything else is allowed.
 * @param port the port to listen on, or 0 for any free port.
 */
class LanServer(
    identity: DeviceIdentity,
    trust: PeerTrust,
    port: Int = 0,
    private val timeouts: LanTimeouts = LanTimeouts(),
) : Closeable {
    private val serverSocket: SSLServerSocket =
        (sslContext(identity, trust).serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            receiveBufferSize = SOCKET_BUFFER_BYTES
            reuseAddress = true
            enabledProtocols = arrayOf(TLS_PROTOCOL)
            needClientAuth = true
            bind(InetSocketAddress(port))
        }

    val port: Int get() = serverSocket.localPort

    /** Waits for the next device to connect. Call [Pending.handshake] on a worker thread. */
    fun accept(): Pending = Pending(serverSocket.accept() as SSLSocket)

    override fun close() = serverSocket.close()

    /** An accepted socket whose TLS handshake hasn't run yet. */
    inner class Pending internal constructor(private val socket: SSLSocket) : Closeable {
        /** Authenticates both sides. Throws (and closes) if the peer isn't trusted. */
        fun handshake(): PeerConnection = socket.establish(timeouts)

        override fun close() = socket.close()
    }
}

/** Connects to devices on the local network (the producer side). */
object LanClient {
    /**
     * Opens an encrypted connection to [address] and proves it reached [expectedPeer]: the certificate
     * presented must hash to exactly that id, so nobody else on the network can pose as the consumer.
     *
     * @param network creates the underlying TCP socket, e.g. one bound to the Wi-Fi network.
     */
    fun connect(
        address: InetSocketAddress,
        identity: DeviceIdentity,
        expectedPeer: DeviceId,
        timeouts: LanTimeouts = LanTimeouts(),
        network: SocketFactory = SocketFactory.getDefault(),
    ): PeerConnection {
        val tcp = network.createSocket()
        try {
            tcp.sendBufferSize = SOCKET_BUFFER_BYTES
            tcp.connect(address, timeouts.connectMillis)
            val factory = sslContext(identity) { it == expectedPeer }.socketFactory
            val socket = factory.createSocket(tcp, address.hostString, address.port, true) as SSLSocket
            socket.enabledProtocols = arrayOf(TLS_PROTOCOL)
            socket.useClientMode = true
            return socket.establish(timeouts)
        } catch (e: IOException) {
            tcp.close()
            throw e
        }
    }
}

private fun SSLSocket.establish(timeouts: LanTimeouts): PeerConnection {
    try {
        tcpNoDelay = true
        soTimeout = timeouts.handshakeMillis
        startHandshake()
        soTimeout = timeouts.readMillis
        val peerCertificate = session.peerCertificates.first() as X509Certificate
        return SocketPeerConnection(this, peerCertificate.deviceId())
    } catch (e: IOException) {
        close()
        throw e
    }
}

internal class SocketPeerConnection(private val socket: SSLSocket, override val peerId: DeviceId) : PeerConnection {
    override val source: BufferedSource = socket.source().buffer()
    override val sink: BufferedSink = socket.sink().buffer()
    override val remoteAddress: String = (socket.remoteSocketAddress as? InetSocketAddress)
        ?.let { "${it.address.hostAddress}:${it.port}" } ?: socket.remoteSocketAddress.toString()

    /** The negotiated protocol, e.g. `TLSv1.3`. */
    val protocol: String get() = socket.session.protocol

    override fun close() = socket.close()
}
