/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What Turbo transfer (docs/PLAN.md §8.3) needs the user to allow. */
object TurboPermissions {
    /** To ask for: nearby devices from Android 13, location before (with coarse, as Android 12 wants both). */
    val requested: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    /** The one Wi-Fi Direct checks. */
    internal val needed: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
}

/**
 * Turbo transfer's direct links, over Wi-Fi Direct (docs/PLAN.md §8.3).
 *
 * The receiving phone creates a group with a fresh name and passphrase, on 5 GHz where it can, and the sending
 * phone joins it with those: Android 10+ sets this up without a system dialog on either phone. Only groups
 * MediaSync created are ever removed; another app's (Android Auto, say) are left alone, and Turbo then isn't used.
 */
@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest")
@SuppressLint("MissingPermission") // Each call that needs a permission runs after availability() found it granted.
@Singleton
class WifiDirect @Inject constructor(@ApplicationContext private val context: Context) : DirectLinks {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    /** The group this phone joined as a sending phone, by name. */
    @Volatile
    private var joined: String? = null

    override fun availability(): TurboAvailability = when {
        manager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ->
            TurboAvailability.UNSUPPORTED

        context.checkSelfPermission(TurboPermissions.needed) != PackageManager.PERMISSION_GRANTED ->
            TurboAvailability.NOT_ALLOWED

        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            !context.getSystemService(LocationManager::class.java).isLocationEnabled -> TurboAvailability.LOCATION_OFF

        else -> TurboAvailability.AVAILABLE
    }

    override fun open(port: Int, credentials: Pair<String, String>, kind: LinkKind): TurboAnswer =
        if (kind == LinkKind.WIFI_DIRECT) {
            openGroup(port, credentials)
        } else {
            TurboAnswer.Unavailable(TurboUnavailable.UNSUPPORTED)
        }

    private fun openGroup(port: Int, credentials: Pair<String, String>): TurboAnswer = when (availability()) {
        TurboAvailability.UNSUPPORTED -> TurboAnswer.Unavailable(TurboUnavailable.UNSUPPORTED)

        TurboAvailability.NOT_ALLOWED, TurboAvailability.LOCATION_OFF ->
            TurboAnswer.Unavailable(TurboUnavailable.NOT_ALLOWED)

        TurboAvailability.AVAILABLE -> runBlocking {
            withTimeoutOrNull(OPEN_TIMEOUT_MILLIS) { createGroup(port, credentials) }
        } ?: TurboAnswer.Unavailable(TurboUnavailable.FAILED).also {
            Diagnostics.log(Area.RECEIVE, "Turbo: the group wasn't up within ${since(0, OPEN_TIMEOUT_MILLIS)}")
            close(credentials.first)
        }
    }

    private suspend fun createGroup(port: Int, credentials: Pair<String, String>): TurboAnswer {
        val channel = channel()
        if (!makeRoom(channel)) return TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        val (name, passphrase) = credentials
        val created = BANDS.any { band ->
            val config = WifiP2pConfig.Builder()
                .setNetworkName(name)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(band)
                .enablePersistentMode(false)
                .build()
            action(Area.RECEIVE, "create a group on ${BAND_NAMES[band]}") { createGroup(channel, config, it) }
        }
        val host = if (created) {
            awaitInfo(channel) { it.groupFormed && it.isGroupOwner }.groupOwnerAddress?.hostAddress
        } else {
            null
        }
        return if (host == null) {
            TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        } else {
            TurboAnswer.Ready(DirectLink(name, passphrase, host, port))
        }
    }

    /** Room for a new group: none is up, or one of ours left over by a crash, which goes. Another app's stays. */
    private suspend fun makeRoom(channel: WifiP2pManager.Channel): Boolean {
        val existing = group(channel) ?: return true
        return existing.isOurs() && action(Area.RECEIVE, "remove the old group") { removeGroup(channel, it) }
    }

    override fun close(networkName: String) {
        if (manager == null) return
        runBlocking {
            withTimeoutOrNull(LEAVE_TIMEOUT_MILLIS) {
                val channel = channel()
                // That group only: a newer one may have replaced it meanwhile.
                if (group(channel)?.networkName == networkName) {
                    action(Area.RECEIVE, "remove the group") { removeGroup(channel, it) }
                }
            }
        }
    }

    override suspend fun join(link: DirectLink, timeoutMillis: Long): JoinedLink? {
        if (availability() != TurboAvailability.AVAILABLE) return null
        return withTimeoutOrNull(timeoutMillis) {
            val channel = channel()
            val config = WifiP2pConfig.Builder()
                .setNetworkName(link.networkName)
                .setPassphrase(link.passphrase)
                .enablePersistentMode(false)
                .build()
            joined = link.networkName
            if (!action(Area.SEND, "join the group") { connect(channel, config, it) }) return@withTimeoutOrNull null
            // Formed, and this phone has its address in it (DHCP takes a moment longer).
            val info = awaitInfo(channel) { info ->
                val owner = info.groupOwnerAddress
                info.groupFormed && !info.isGroupOwner && owner != null && hasAddressNear(owner)
            }
            info.groupOwnerAddress?.let(::JoinedLink)
        }
    }

    override fun leave() {
        val name = joined ?: return
        joined = null
        runBlocking {
            withTimeoutOrNull(LEAVE_TIMEOUT_MILLIS) {
                val channel = channel()
                action(Area.SEND, "stop joining") { cancelConnect(channel, it) }
                if (group(channel)?.networkName ==
                    name
                ) {
                    action(Area.SEND, "leave the group") { removeGroup(channel, it) }
                }
            }
        }
    }

    /** Answers arrive here, not on the main thread, which may be the one waiting for them. */
    private val callbacks: Looper by lazy { HandlerThread("mediasync-wifi-direct").apply { start() }.looper }

    @Synchronized
    private fun channel(): WifiP2pManager.Channel = channel ?: checkNotNull(manager)
        .initialize(context, callbacks) { channel = null }
        .also { channel = it }

    /** Runs a Wi-Fi Direct request; `true` if Android took it. Why not goes into the diagnostic log, under [area]. */
    private suspend fun action(
        area: Area,
        what: String,
        call: WifiP2pManager.(WifiP2pManager.ActionListener) -> Unit,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        checkNotNull(manager).call(
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onFailure(reason: Int) {
                    Diagnostics.log(area, "Turbo: Wi-Fi Direct couldn't $what (${reasonOf(reason)})")
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }

    private suspend fun group(channel: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { continuation ->
            checkNotNull(manager).requestGroupInfo(channel) { group ->
                if (continuation.isActive) continuation.resume(group)
            }
        }

    /** Asks until [ready]; the caller's timeout ends the wait. */
    private suspend fun awaitInfo(channel: WifiP2pManager.Channel, ready: (WifiP2pInfo) -> Boolean): WifiP2pInfo {
        while (true) {
            val info = suspendCancellableCoroutine { continuation ->
                checkNotNull(manager).requestConnectionInfo(channel) { info ->
                    if (continuation.isActive) continuation.resume(info)
                }
            }
            if (info != null && ready(info)) return info
            delay(POLL_MILLIS)
        }
    }

    private fun WifiP2pGroup.isOurs() = networkName.orEmpty().contains(DirectLink.NAME_MARKER)

    /** This phone has an IPv4 address in the group owner's network. */
    private fun hasAddressNear(owner: InetAddress): Boolean {
        val prefix = owner.address.copyOf(PREFIX_BYTES)
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { it is Inet4Address && it != owner && it.address.copyOf(PREFIX_BYTES).contentEquals(prefix) }
    }

    private fun reasonOf(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        else -> "reason $reason"
    }

    private companion object {
        /** 5 GHz first: a clean channel, and the fastest; any band if the phone can't (docs/PLAN.md §7). */
        val BANDS = listOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        val BAND_NAMES = mapOf(
            WifiP2pConfig.GROUP_OWNER_BAND_5GHZ to "5 GHz",
            WifiP2pConfig.GROUP_OWNER_BAND_AUTO to "any band",
        )
        const val OPEN_TIMEOUT_MILLIS = 15_000L
        const val LEAVE_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 300L
        const val PREFIX_BYTES = 3 // Android's Wi-Fi Direct groups are /24 networks.
    }
}
