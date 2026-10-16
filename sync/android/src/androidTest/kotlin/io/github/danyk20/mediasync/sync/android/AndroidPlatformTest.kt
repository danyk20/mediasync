/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.DirectLink
import io.github.danyk20.mediasync.sync.engine.LinkKind
import io.github.danyk20.mediasync.sync.engine.TurboAnswer
import io.github.danyk20.mediasync.sync.engine.TurboUnavailable
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android's own services on API 29 and 36 (CI): device name, the Wi-Fi network and mDNS discovery. */
@RunWith(AndroidJUnit4::class)
class AndroidPlatformTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deviceHasANameAndVersion() {
        val info = AndroidDeviceInfo(
            context,
            schedule = { SyncSchedule.WhileCharging },
            details = { PhotoDetails.REMOVE_LOCATION },
            order = { SendingOrder.OLDEST_FIRST },
            turboKeys = TurboKeyStore(context),
            rendezvous = { "https://rendezvous.example.com" },
            relayVideos = { true },
        )
        val device = info.local()

        device.name.shouldNotBeBlank()
        device.appVersion.shouldNotBeBlank()
        device.schedule shouldBe SyncSchedule.WhileCharging
        info.photoDetails() shouldBe PhotoDetails.REMOVE_LOCATION
        info.sendingOrder() shouldBe SendingOrder.OLDEST_FIRST
        info.videosOverRelay() shouldBe true
        device.rendezvousUrl shouldBe "https://rendezvous.example.com"
    }

    @Test
    fun emulatorWifiHasALocalAddress() {
        // Emulators expose their network as Wi-Fi, like a phone at home.
        requireWifi()
        AndroidLocalNetwork(context).localAddresses().shouldNotBeEmpty()
    }

    @Test
    fun connectionsToTheWifiGoThroughTheWifi() {
        requireWifi()
        val network = AndroidLocalNetwork(context)
        val wifiAddress = pairingAddresses(network.localAddresses()).first() // What a pairing code would list.

        ServerSocket(0, 1, wifiAddress).use { server ->
            val target = InetSocketAddress(wifiAddress, server.localPort)
            val factory = network.factoryFor(target)

            factory shouldNotBe SocketFactory.getDefault()
            factory.createSocket().use { socket ->
                socket.connect(target, 5_000)
                socket.isConnected shouldBe true
            }
        }
    }

    @Test
    fun sessionsKeepTheWifiAwakeAndLetItRestAfterwards() {
        val network = AndroidLocalNetwork(context)

        val held = network.keepingAwake { wifiLocks() }

        held shouldBe true
        wifiLocks() shouldBe false
    }

    /** Whether this app holds a Wi-Fi lock: listed under "Locks held:" (newer Androids also log past ones). */
    private fun wifiLocks(): Boolean {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys wifi")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
        var held = false
        return dump.lines().any { line ->
            val text = line.trim()
            when {
                text == "Locks held:" -> held = true
                text.isEmpty() || text.startsWith("Locks ") || text.startsWith("Active lock owners") -> held = false
            }
            held && "MediaSync:session" in text
        }
    }

    @Test
    fun anAdvertisedReceiverIsFoundByItsServiceId() = runBlocking<Unit> {
        requireWifi()
        val discovery = NsdServiceDiscovery(context)
        val serviceId = "test${System.nanoTime() % 1_000_000_000}"

        discovery.advertise(port = 47_421, serviceId = serviceId).use {
            val found = discovery.find(serviceId, timeoutMillis = 15_000)

            found?.substringAfterLast(':') shouldBe "47421"
        }
    }

    @Test
    fun anUnknownServiceIdIsNotFound() = runBlocking<Unit> {
        NsdServiceDiscovery(context).find("not-advertised", timeoutMillis = 2_000).shouldBeNull()
    }

    @Test
    fun aHotPhoneOrALowBatteryPausesBackgroundWork() {
        val conditions = AndroidDeviceConditions(context)
        try {
            shell("dumpsys battery set level 50")
            awaitValue(null) { conditions.pauseReason() }.shouldBeNull()

            shell("dumpsys battery set level 19")
            awaitValue(PauseReason.LOW_BATTERY) { conditions.pauseReason() } shouldBe PauseReason.LOW_BATTERY

            shell("dumpsys battery set level 50")
            shell("cmd thermalservice override-status ${PowerManager.THERMAL_STATUS_SEVERE}")
            awaitValue(PauseReason.HOT) { conditions.pauseReason() } shouldBe PauseReason.HOT
        } finally {
            shell("cmd thermalservice reset")
            shell("dumpsys battery reset")
        }
    }

    @Test
    fun nightlySyncsNeedUnrestrictedBatteryOnAndroid12AndUp() {
        val setup = BackgroundSetup(context)
        val app = context.packageName
        try {
            shell("cmd deviceidle whitelist -$app")
            setup.batteryRestricted() shouldBe (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

            shell("cmd deviceidle whitelist +$app") // What choosing Unrestricted does.
            setup.batteryRestricted() shouldBe false
        } finally {
            shell("cmd deviceidle whitelist -$app")
        }
    }

    @Test
    fun progressNotificationsNeedThePermissionOnAndroid13AndUp() {
        val setup = BackgroundSetup(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            setup.canNotify() shouldBe true
            return
        }
        // Revoking it would end this test's process, so this only grants it.
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        setup.canNotify() shouldBe granted

        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        setup.canNotify() shouldBe true
    }

    @Test
    fun turboNeedsWifiDirectAndItsPermissionAndNeverLeavesAGroupBehind() = runBlocking<Unit> {
        val direct = WifiDirect(context)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            direct.availability() shouldBe TurboAvailability.UNSUPPORTED
            return@runBlocking
        }
        shell("pm grant ${context.packageName} ${TurboPermissions.needed}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Before Android 13, Wi-Fi Direct also needs location on.
            shell("settings put secure location_mode 3")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shell("cmd location set-location-enabled true")
        }
        assumeTrue("Location stays off here", direct.availability() != TurboAvailability.LOCATION_OFF)
        direct.availability() shouldBe TurboAvailability.AVAILABLE

        // Emulators have Wi-Fi Direct turned off: the group fails, cleanly. On a phone, it's MediaSync's own.
        when (val answer = direct.open(port = 47_420)) {
            is TurboAnswer.Ready -> {
                answer.link.networkName shouldContain DirectLink.NAME_MARKER
                direct.close(answer.link.networkName)
            }

            is TurboAnswer.Unavailable -> answer.reason shouldBe TurboUnavailable.FAILED
        }
        val nowhere = DirectLink("DIRECT-zz-MediaSync", "no-such-group-passphrase", "192.168.49.1", 47_420)
        direct.join(nowhere, timeoutMillis = 5_000).shouldBeNull()
        direct.leave()
    }

    @Test
    fun wifiAwareIsLeftAloneOnPhonesWithoutIt() = runBlocking<Unit> {
        val aware = WifiAware(context)
        // Phones with Wi-Fi Aware are tested by hand (docs/PLAN.md §8.3); emulators have none.
        assumeTrue(
            "This device has Wi-Fi Aware",
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
        )

        aware.awareAvailable() shouldBe false
        aware.open(47_420, DirectLink.awareCredentials(), LinkKind.WIFI_AWARE) shouldBe
            TurboAnswer.Unavailable(TurboUnavailable.FAILED)
        val link =
            DirectLink("MediaSync-abcdefghijklmnop", "no-such-service-passphrase", "", 47_420, LinkKind.WIFI_AWARE)
        aware.join(link, timeoutMillis = 1_000).shouldBeNull()
        aware.leave()
    }

    /**
     * Emulators join their virtual Wi-Fi ("AndroidWifi") a while after booting, which is when CI starts testing,
     * and drop it when the host's network changes. Asks for it and waits; a test without it is skipped, not failed.
     */
    private fun requireWifi() {
        // Connecting while connected makes the Wi-Fi drop for a moment, and its address with it: a test then bound to
        // an address that was gone (EADDRNOTAVAIL), and the next tests ran on a network still settling.
        if (wifiIsUp(QUICK_CHECK_SECONDS)) return
        shell("svc wifi enable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shell("cmd wifi connect-network AndroidWifi open")
        assumeTrue("This emulator has no Wi-Fi", wifiIsUp(WIFI_TIMEOUT_SECONDS))
    }

    /** Android tells apps about the battery by a broadcast, which arrives a moment after the change. */
    private fun <T> awaitValue(expected: T, read: () -> T): T {
        val deadline = SystemClock.uptimeMillis() + SETTLE_MILLIS
        var value = read()
        while (value != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MILLIS)
            value = read()
        }
        return value
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } // Waits for the command to end.
    }

    /** Whether a Wi-Fi network with an IPv4 address appears within [WIFI_TIMEOUT_SECONDS]. */
    // Whether a Wi-Fi network with an IPv4 address shows up within timeoutSeconds.
    private fun wifiIsUp(timeoutSeconds: Long): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val ready = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (properties.linkAddresses.any { it.address is Inet4Address }) ready.countDown()
            }
        }
        val wifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(wifi, callback)
        return try {
            ready.await(timeoutSeconds, TimeUnit.SECONDS)
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
    }

    private companion object {
        const val WIFI_TIMEOUT_SECONDS = 60L
        const val QUICK_CHECK_SECONDS = 2L
        const val SETTLE_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
    }
}
