/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class PlatformTest {

    @Test
    fun aSendingPhoneTriesWhereTheReceivingPhoneSaidItIsOnThisWifiThenWhereItWasLastReached() {
        val onHomeWifi = { host: String -> host == "10.128.0.57" }

        // On the Wi-Fi the receiving phone said it's on: there first, then where it was reached on another one.
        addressesToTry("192.168.43.20:47420", listOf("10.128.0.57:47420"), onHomeWifi) shouldBe
            listOf("10.128.0.57:47420", "192.168.43.20:47420")
        // Elsewhere: what it said is no use, as it isn't on a network this phone is on; what worked before may be.
        addressesToTry("192.168.43.20:47420", listOf("10.128.0.57:47420")) { false } shouldBe
            listOf("192.168.43.20:47420")
        // Said and reached at the same place: once. Not said yet (an older version): where it was reached.
        addressesToTry("10.128.0.57:47420", listOf("10.128.0.57:47420"), onHomeWifi) shouldBe
            listOf("10.128.0.57:47420")
        addressesToTry("192.168.43.20:47420", emptyList(), onHomeWifi) shouldBe listOf("192.168.43.20:47420")
        // Never anything that isn't a literal address.
        addressesToTry(null, listOf("evil.lan:47420", "10.128.0.57:47420")) { true } shouldBe
            listOf("10.128.0.57:47420")
        addressesToTry(null, emptyList()) { true } shouldBe emptyList()
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
