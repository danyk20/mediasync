/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
        val device = AndroidDeviceInfo(context).local()

        device.name.shouldNotBeBlank()
        device.appVersion.shouldNotBeBlank()
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

    /**
     * Emulators join their virtual Wi-Fi ("AndroidWifi") a while after booting, which is when CI starts testing,
     * and drop it when the host's network changes. Asks for it and waits; a test without it is skipped, not failed.
     */
    private fun requireWifi() {
        shell("svc wifi enable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shell("cmd wifi connect-network AndroidWifi open")
        assumeTrue("This emulator has no Wi-Fi", wifiIsUp())
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } // Waits for the command to end.
    }

    /** Whether a Wi-Fi network with an IPv4 address appears within [WIFI_TIMEOUT_SECONDS]. */
    private fun wifiIsUp(): Boolean {
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
            ready.await(WIFI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
    }

    private companion object {
        const val WIFI_TIMEOUT_SECONDS = 60L
    }
}
