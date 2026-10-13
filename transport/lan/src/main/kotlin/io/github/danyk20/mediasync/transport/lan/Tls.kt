/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.core.model.DeviceId
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** Only TLS 1.3: forward secrecy and an encrypted certificate exchange, on every supported Android. */
internal const val TLS_PROTOCOL = "TLSv1.3"

/** Decides whether a peer that proved ownership of a certificate may connect. */
fun interface PeerTrust {
    fun isTrusted(peer: DeviceId): Boolean
}

internal fun sslContext(identity: DeviceIdentity, trust: PeerTrust): SSLContext =
    SSLContext.getInstance(TLS_PROTOCOL).apply {
        init(arrayOf(IdentityKeyManager(identity)), arrayOf(PinningTrustManager(trust)), null)
    }

/** Always presents this device's own certificate, as server and as client. */
private class IdentityKeyManager(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
    private val algorithm = identity.privateKey.algorithm

    private fun aliasFor(keyTypes: Array<out String>?) = if (keyTypes == null || algorithm in keyTypes) ALIAS else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        aliasFor(keyType)

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ) = aliasFor(keyType)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
        aliasFor(keyType?.let { arrayOf(it) })

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        aliasFor(keyType?.let { arrayOf(it) })

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)

    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private companion object {
        const val ALIAS = "mediasync-identity"
    }
}

/**
 * Trusts a certificate only if its hash is a known device (certificate pinning). There are no certificate
 * authorities: pairing exchanges the pins in person, through the QR code.
 */
private class PinningTrustManager(private val trust: PeerTrust) : X509ExtendedTrustManager() {

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The peer presented no certificate")
        val peer = leaf.deviceId()
        if (!trust.isTrusted(peer)) throw CertificateException("Device ${peer.short} is not trusted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        verify(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        verify(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
