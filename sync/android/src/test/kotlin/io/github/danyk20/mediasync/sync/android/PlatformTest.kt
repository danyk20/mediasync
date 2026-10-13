/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.InetAddress
import org.junit.Test

class PlatformTest {

    @Test
    fun addressesRoundTrip() {
        val v4 = formatAddress(InetAddress.getByName("192.168.1.20"), 47_420)
        val v6 = formatAddress(InetAddress.getByName("fd00::1"), 47_420)

        v4 shouldBe "192.168.1.20:47420"
        v6 shouldBe "[fd00:0:0:0:0:0:0:1]:47420"
        parseAddress(v4)?.port shouldBe 47_420
        parseAddress(v6)?.address shouldBe InetAddress.getByName("fd00::1")
    }

    @Test
    fun pairingCodesListIpv4AddressesOnlyWhenThereAreAny() {
        val addresses = listOf("fe80::1", "fec0::2", "fd12::3", "192.168.1.20", "2001:db8::4")
            .map(InetAddress::getByName)

        pairingAddresses(addresses) shouldBe listOf(InetAddress.getByName("192.168.1.20"))
    }

    @Test
    fun withoutIpv4PairingCodesListLastingIpv6AddressesPrivateFirst() {
        val addresses = listOf("fe80::1", "fec0::2", "2001:db8::4", "2001:db8::5", "fd12::3")
            .map(InetAddress::getByName)

        pairingAddresses(addresses) shouldBe listOf("fd12::3", "2001:db8::4").map(InetAddress::getByName)
    }

    @Test
    fun onlyLiteralAddressesAreAccepted() {
        listOf("", "nohost", ":47420", "192.168.1.20:", "192.168.1.20:99999", "example.com:443", "evil.lan:1")
            .forEach { parseAddress(it).shouldBeNull() }
    }

    @Test
    fun serviceIdsHideTheDeviceId() {
        val id = deviceId(5)

        serviceIdOf(id).length shouldBe 16
        serviceIdOf(id) shouldBe serviceIdOf(id)
        serviceIdOf(id) shouldNotBe serviceIdOf(deviceId(6))
        id.hex.contains(serviceIdOf(id)) shouldBe false
    }
}
