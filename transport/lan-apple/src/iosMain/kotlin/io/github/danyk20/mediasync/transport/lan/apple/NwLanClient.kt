/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import platform.Network.nw_connection_create
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_protocol_options_t
import platform.Network.nw_tcp_options_set_connection_timeout
import platform.Network.nw_tcp_options_set_no_delay
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.SecCertificateCopyData
import platform.Security.sec_certificate_copy_ref
import platform.Security.sec_protocol_metadata_access_peer_certificate_chain
import platform.Security.sec_protocol_metadata_t
import platform.Security.sec_protocol_options_set_local_identity
import platform.Security.sec_protocol_options_set_max_tls_protocol_version
import platform.Security.sec_protocol_options_set_min_tls_protocol_version
import platform.Security.sec_protocol_options_set_peer_authentication_required
import platform.Security.sec_protocol_options_set_tls_resumption_enabled
import platform.Security.sec_protocol_options_set_tls_tickets_enabled
import platform.Security.sec_protocol_options_set_verify_block
import platform.Security.sec_protocol_options_t
import platform.Security.tls_protocol_version_TLSv13
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t

/**
 * Connects to a MediaSync device over TLS 1.3 with mutual authentication, using Network.framework.
 *
 * The server must present exactly [expectedPeer]'s certificate (its pin). We present [identity] as the client
 * certificate. Session resumption and tickets are off, because a resumed session skips the verify block.
 */
object NwLanClient {
    /**
     * Opens a connection to a literal IP address and blocks until the TLS handshake has finished.
     *
     * Any `waiting` state counts as failure (connection refused, no route, a wrong pin): Network.framework would
     * otherwise retry forever. The connection is cancelled and an [IOException] is thrown.
     */
    fun connect(
        host: String,
        port: Int,
        identity: AppleIdentity,
        expectedPeer: DeviceId,
        timeouts: AppleLanTimeouts = AppleLanTimeouts(),
    ): PeerConnection {
        val queue = dispatch_queue_create("mediasync.lan", null).orFail("dispatch_queue_create failed")
        val verdict = PeerVerdict(expectedPeer)
        val parameters = nw_parameters_create_secure_tcp(
            { tlsOptions -> configureTls(nw_tls_copy_sec_protocol_options(tlsOptions), identity, verdict, queue) },
            { tcpOptions -> configureTcp(tcpOptions, timeouts) },
        ).orFail("nw_parameters_create_secure_tcp failed")
        val connection = nw_connection_create(nw_endpoint_create_host(host, port.toString()), parameters)
            .orFail("nw_connection_create failed")

        val peer = NwPeerConnection(connection, queue, verdict, timeouts, "$host:$port")
        val outcome = peer.start(timeouts.connectMillis + timeouts.handshakeMillis)
        // Returned only when the handshake is ready and the peer is the expected device. Every other case is closed.
        if (outcome == Handshake.Ready && verdict.trusted == expectedPeer) return peer
        peer.close()
        throw IOException(describeFailure(host, port, outcome, verdict))
    }

    private fun describeFailure(host: String, port: Int, outcome: Handshake?, verdict: PeerVerdict): String {
        if (verdict.pinMismatch) {
            val found = verdict.peer?.short ?: "no certificate"
            return "$host:$port presented $found, not the expected device (TLS pin mismatch)"
        }
        return when (outcome) {
            null -> "No answer from $host:$port in time (connect and handshake)"

            is Handshake.Failed -> "Connection to $host:$port ${if (outcome.waiting) "is waiting" else "failed"}, " +
                "error ${outcome.code ?: "unknown"}"

            Handshake.Ready -> "The handshake with $host:$port finished without a peer certificate"
        }
    }

    private fun configureTls(
        options: sec_protocol_options_t,
        identity: AppleIdentity,
        verdict: PeerVerdict,
        queue: dispatch_queue_t,
    ) {
        sec_protocol_options_set_min_tls_protocol_version(options, tls_protocol_version_TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(options, tls_protocol_version_TLSv13)
        sec_protocol_options_set_tls_resumption_enabled(options, false)
        sec_protocol_options_set_tls_tickets_enabled(options, false)
        sec_protocol_options_set_peer_authentication_required(options, true)
        sec_protocol_options_set_local_identity(options, identity.secIdentity)
        sec_protocol_options_set_verify_block(
            options,
            { metadata, _, complete -> complete?.invoke(verifyPeer(metadata, verdict)) },
            queue,
        )
    }

    /**
     * The body of the verify block. Nothing may escape a native callback, so any exception rejects the peer and the
     * caller completes with false. The exception is not kept: a native callback has no way to pass it on.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun verifyPeer(metadata: sec_protocol_metadata_t, verdict: PeerVerdict): Boolean = try {
        verdict.check(metadata)
    } catch (error: Throwable) {
        verdict.reject()
        false
    }

    private fun configureTcp(options: nw_protocol_options_t, timeouts: AppleLanTimeouts) {
        nw_tcp_options_set_no_delay(options, true)
        nw_tcp_options_set_connection_timeout(
            options,
            ((timeouts.connectMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toUInt(),
        )
    }

    private const val MILLIS_PER_SECOND = 1_000L
}

/**
 * What the verify block learned about the server: the hash of its leaf certificate (the peer id) and whether it
 * matched the expected pin. Written on the connection's queue and read after the handshake's semaphore.
 */
internal class PeerVerdict(private val expected: DeviceId) {
    var peer: DeviceId? = null
        private set
    var pinMismatch = false
        private set

    /** The verified peer, or null when the pin did not match or the check never ran. */
    val trusted: DeviceId? get() = peer.takeIf { !pinMismatch }

    /** Returns true only when the first certificate of the chain hashes to the expected id. */
    fun check(metadata: sec_protocol_metadata_t): Boolean {
        val leaf = leafCertificateDer(metadata)
        val found = leaf?.let { DeviceId.of(it.sha256()) }
        peer = found
        val matches = found != null && found == expected
        pinMismatch = !matches
        return matches
    }

    /** Records a failed check, for an exception raised while checking. */
    fun reject() {
        peer = null
        pinMismatch = true
    }

    /**
     * The DER of the first certificate in the peer chain, the leaf. Only index 0 is ever hashed: when that entry is
     * missing or cannot be read, the result is null (a failure), and a later certificate is never used instead.
     */
    private fun leafCertificateDer(metadata: sec_protocol_metadata_t): ByteArray? {
        var first = true
        var leaf: ByteArray? = null
        sec_protocol_metadata_access_peer_certificate_chain(metadata) { certificate ->
            if (first) {
                first = false
                if (certificate != null) {
                    val reference = sec_certificate_copy_ref(certificate)
                    if (reference != null) {
                        try {
                            val data = SecCertificateCopyData(reference)
                            if (data != null) {
                                try {
                                    leaf = data.toByteArray()
                                } finally {
                                    release(data)
                                }
                            }
                        } finally {
                            release(reference)
                        }
                    }
                }
            }
        }
        return leaf
    }
}
