/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
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
        AndroidLocalNetwork(context).localAddresses().shouldNotBeEmpty()
    }

    @Test
    fun connectionsToTheWifiGoThroughTheWifi() {
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
}
