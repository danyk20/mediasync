/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pairing link on Android's own Java library (API 29 and 36 in CI). Its JVM tests can't catch a JDK
 * method that older Android versions lack: `URLEncoder.encode(String, Charset)` once crashed Android 10–12.
 */
@RunWith(AndroidJUnit4::class)
class PairingLinkOnDeviceTest {

    @Test
    fun aLinkSurvivesTheRoundTrip() {
        val link = PairingLink(
            consumer = DeviceId.of(ByteArray(32) { it.toByte() }),
            token = ByteArray(PairingLink.TOKEN_BYTES) { (it * 7).toByte() }.toByteString(),
            addresses = listOf("192.168.1.20:47420", "[fd00::1]:47420"),
            consumerName = "Daniel's Pixel 2 XL & co.",
        )

        PairingLink.parse(link.toString()) shouldBe link
    }
}
