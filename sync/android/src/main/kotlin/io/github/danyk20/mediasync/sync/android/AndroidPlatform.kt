/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest")
@Singleton
class AndroidDeviceInfo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val schedule: ScheduleSource,
    private val details: PhotoDetailsSource,
    private val order: SendingOrderSource,
    private val turboKeys: TurboKeyStore,
    private val rendezvous: RendezvousSource,
    private val relayVideos: RelayVideosSource,
) : DeviceInfo {
    private val device by lazy { LocalDevice(name(), version()) }

    /** As a receiving phone, it also tells its schedule and Turbo key; sending phones ignore those. */
    override fun local(): LocalDevice = device.copy(
        schedule = schedule.current(),
        turboKey = turboKeys.current(),
        rendezvousUrl = rendezvous.current(),
    )

    override fun photoDetails(): PhotoDetails = details.current()

    override fun sendingOrder(): SendingOrder = order.current()

    override fun videosOverRelay(): Boolean = relayVideos.allowed()

    /** The name the user gave the phone (Settings → About phone), else its model. */
    private fun name(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun version(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: "unknown"
    }
}

/** Battery level and temperature from Android, for the background guards (docs/PLAN.md §10.5). */
@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest")
@Singleton
class AndroidDeviceConditions @Inject constructor(@ApplicationContext private val context: Context) :
    DeviceConditions {

    override fun pauseReason(): PauseReason? {
        val power = context.getSystemService(PowerManager::class.java)
        return when {
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> PauseReason.HOT
            (batteryPercent() ?: PERCENT) < MIN_BATTERY_PERCENT -> PauseReason.LOW_BATTERY
            else -> null
        }
    }

    override fun lowForTurbo(): Boolean {
        val battery = battery() ?: return false
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return !plugged && (batteryPercent() ?: PERCENT) < TURBO_WARNING_PERCENT
    }

    private fun battery(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** From the last battery broadcast: what the system shows, unlike a direct reading from the battery. */
    private fun batteryPercent(): Int? {
        val battery = battery() ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else level * PERCENT / scale
    }

    private companion object {
        const val MIN_BATTERY_PERCENT = 20
        const val TURBO_WARNING_PERCENT = 30
        const val PERCENT = 100
    }
}

/**
 * What background syncs need the user to allow (docs/PLAN.md §10.3); the home screens ask for it.
 *
 * - Android 12+ starts a foreground service from the background, which a sync at night needs to run longer
 *   than 10 minutes, only for apps whose battery use is *Unrestricted*.
 * - Android 13+ shows the progress notification only with the user's permission.
 */
@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest")
@Singleton
class BackgroundSetup @Inject constructor(@ApplicationContext private val context: Context) {

    fun batteryRestricted(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * The Wi-Fi and Ethernet networks this phone is on, whether or not Android uses them for the internet. While
 * a Wi-Fi has no (verified) internet, Android sends traffic over mobile data instead, yet phones on that
 * Wi-Fi still reach each other through it.
 */
@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest")
@Singleton
class AndroidLocalNetwork @Inject constructor(@ApplicationContext context: Context) :
    AddressProvider,
    LanSockets {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    /**
     * Holds Wi-Fi locks while a session runs: Wi-Fi power saving would otherwise slow transfers down, the screen
     * being off at night. No wake lock: background jobs and foreground services already keep the phone awake.
     */
    override fun canUseMoreConnections(): Boolean = power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE

    override fun <T> keepingAwake(session: () -> T): T {
        @Suppress("DEPRECATION") // Low latency needs the screen on; high performance covers the night.
        val locks = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            .map { mode -> wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply { setReferenceCounted(false) } }
        locks.forEach { it.acquire() }
        try {
            return session()
        } finally {
            locks.forEach { if (it.isHeld) it.release() }
        }
    }

    override fun localAddresses(): List<InetAddress> =
        localNetworks().flatMap { (_, links) -> links.linkAddresses.map { it.address } }

    /** Binds to the local network that has a direct route to [address], else to any local network. */
    override fun factoryFor(address: InetSocketAddress): SocketFactory {
        val networks = localNetworks()
        val direct = networks.firstOrNull { (_, links) ->
            links.routes.any { !it.isDefaultRoute && it.matches(address.address) }
        }
        return (direct ?: networks.firstOrNull())?.first?.socketFactory ?: SocketFactory.getDefault()
    }

    // A snapshot is all pairing and syncing need; a NetworkCallback would have to be registered in advance.
    @Suppress("DEPRECATION")
    private fun localNetworks(): List<Pair<Network, LinkProperties>> = connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        connectivity.getLinkProperties(network)?.takeIf { local }?.let { network to it }
    }
}

/** DNS-SD over mDNS through Android's [NsdManager]. */
@ExercisedOnDevice(coveredBy = "sync:android AndroidPlatformTest")
@Singleton
class NsdServiceDiscovery @Inject constructor(@ApplicationContext context: Context) : ServiceDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)

    override fun advertise(port: Int, serviceId: String): Closeable {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ID_ATTRIBUTE, serviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Closeable { runCatching { nsd.unregisterService(listener) } }
    }

    override suspend fun find(serviceId: String, timeoutMillis: Long): String? = withTimeoutOrNull(timeoutMillis) {
        discovered()
            .map { resolve(it) } // One at a time: older Android versions reject parallel resolves.
            .firstOrNull { it?.serviceId == serviceId }
            ?.address
    }

    private fun discovered() = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                trySend(info)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private suspend fun resolve(info: NsdServiceInfo): Resolved? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                    if (continuation.isActive) {
                        continuation.resume(
                            updated.toResolved(updated.hostAddresses.firstOrNull()),
                        )
                    }
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceLost() = Unit

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(info, Runnable::run, callback)
            continuation.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(resolved.toResolved(resolved.host))
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }
    }

    private class Resolved(val serviceId: String?, val address: String?)

    private fun NsdServiceInfo.toResolved(host: InetAddress?) = Resolved(
        serviceId = attributes[ID_ATTRIBUTE]?.decodeToString(),
        address = host?.let { formatAddress(it, port) },
    )

    private companion object {
        const val SERVICE_TYPE = "_mediasync._tcp."
        const val SERVICE_NAME = "MediaSync"
        const val ID_ATTRIBUTE = "id"
    }
}

private const val WIFI_LOCK_TAG = "MediaSync:session"
