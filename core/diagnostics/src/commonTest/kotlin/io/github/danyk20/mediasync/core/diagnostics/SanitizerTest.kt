/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class SanitizerTest {

    @Test
    fun pathsAndAddressesNeverReachTheLog() {
        val cleaned = Sanitizer.clean(
            "Can't open content://media/external/images/media/42 at /storage/emulated/0/DCIM/Camera/PXL_1.jpg " +
                "or file:///sdcard/x.jpg; peer 192.168.1.37:47420, fe80::1c2b:3aff:fe4d%wlan0 and 2001:db8:0:0:1:2:3:4",
        )

        listOf("media/42", "DCIM", "PXL_1", "sdcard", ".37", "fe80", "2001:db8").forEach { cleaned shouldNotContain it }
        cleaned shouldContain "<content uri>"
        cleaned shouldContain "<path>"
        cleaned shouldContain "192.168.1.x:47420" // The subnet stays: same Wi-Fi or not.
    }

    @Test
    fun tokensNeverReachTheLog() {
        val cleaned = Sanitizer.clean("Unexpected char 0x0a at 7 in header value: Bearer cfut_abc123-XYZ, retrying")

        cleaned shouldNotContain "cfut_abc123"
        cleaned shouldContain "Bearer <token>, retrying"
    }

    @Test
    fun ordinaryMessagesStayAsTheyAre() {
        val message = "Sent 12 of 40 (3.2 MB) in 00:01:07, retry at 03:15, schedule daily@03:00, ratio 1.5"

        Sanitizer.clean(message) shouldBe message
    }
}
