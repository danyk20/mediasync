/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.crypto.IdentityProvider
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.rendezvous.InternetPath
import io.github.danyk20.mediasync.transport.rendezvous.RelayCounter
import io.github.danyk20.mediasync.transport.rendezvous.RendezvousSigner
import io.github.danyk20.mediasync.transport.webrtc.WebRtcLink
import java.io.Closeable
import java.net.URI
import java.security.Signature
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import okio.ByteString

/**
 * Internet sync over WebRTC (docs/PLAN.md §8.4). Requests to the Worker are signed with this phone's keystore
 * identity, the one paired phones pin (ADR 0009).
 */
@ExercisedOnDevice(coveredBy = "sync:android InternetLinksTest")
@Singleton
class AndroidInternetLinks @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val identity: IdentityProvider,
    private val mobileData: MobileDataSource,
    private val alerts: RelayAlerts,
    private val usage: MobileDataUsage,
    private val pushTokens: PushTokens,
) : InternetLinks {
    private val links by lazy { WebRtcLink(context) }
    private val signer by lazy {
        val device = identity.identity()
        RendezvousSigner(device.certificate.encoded, { data ->
            Signature.getInstance(SIGNATURE).run {
                initSign(device.privateKey)
                update(data)
                sign()
            }
        })
    }
    private val sending by lazy { path(Area.SEND) }
    private val receiving by lazy { path(Area.RECEIVE) }

    override fun allowed(): Boolean = when (onMobileData()) {
        null -> false
        false -> true
        true -> mobileData.allowed() && (mobileDataLeft() ?: 0L) > 0
    }

    override fun mobileDataLeft(): Long? {
        if (onMobileData() != true) return null
        val cap = mobileData.capBytes() ?: return Long.MAX_VALUE
        return (cap - usage.current()).coerceAtLeast(0)
    }

    override fun countMobileData(bytes: Long) = usage.add(bytes)

    /** Whether the phone is on a metered network (mobile data, or a hotspot set as one); `null` without a network. */
    private fun onMobileData(): Boolean? {
        val network = context.getSystemService(ConnectivityManager::class.java)
        return network.isActiveNetworkMetered.takeIf { network.activeNetwork != null }
    }

    override fun connect(consumer: DeviceId, worker: String, pairSecret: ByteString): PeerConnection =
        sending.connect(consumer, URI(worker), pairSecret.toByteArray())

    override fun listen(worker: String, pairs: Map<DeviceId, ByteString>, serve: (PeerConnection) -> Unit): Closeable =
        receiving.listen(URI(worker), pairs.mapValues { it.value.toByteArray() }, pushTokens.current(), serve)

    override fun relayUsage(worker: String, producer: DeviceId): RelayUsage =
        receiving.relayMeter(URI(worker), producer).let { meter ->
            RelayUsage(
                usedBytes = meter.usedBytes,
                limitBytes = meter.limitBytes,
                freeBytes = meter.freeAllowanceBytes,
                available = meter.relayAvailable,
                resetsAt = runCatching { Instant.parse(meter.period.end) }.getOrNull(),
            )
        }.also(alerts::check) // Whenever the meter is read: the user hears when it runs low.

    override fun wakeCheck(worker: String, producer: DeviceId) = receiving.wakeCheck(URI(worker), producer)

    private fun path(area: Area) = InternetPath(signer, links, log = { message, error ->
        val entry = "Internet: $message"
        if (error == null) Diagnostics.log(area, entry) else Diagnostics.log(area, entry, error)
    })

    private companion object {
        /** What the keystore key allows, and the Worker checks: ECDSA P-256 over SHA-256, DER. */
        const val SIGNATURE = "SHA256withECDSA"
    }
}

/** How an internet connection runs, for the log: directly between the phones, or through the relay (PLAN §8.4). */
internal fun PeerConnection.internetRoute(): String =
    if ((this as? RelayCounter)?.throughRelay() == true) "through the relay" else "directly"
