/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HostPortTest {

    @Test
    fun ipv4RoundTrips() {
        val parsed = parseHostPort("192.168.1.20:47420")

        parsed shouldBe HostPort("192.168.1.20", 47_420)
        parsed.toString() shouldBe "192.168.1.20:47420"
    }

    @Test
    fun ipv6RoundTripsWithBrackets() {
        parseHostPort("[fd00::1]:47420") shouldBe HostPort("fd00::1", 47_420)
        HostPort("fd00::1", 47_420).toString() shouldBe "[fd00::1]:47420"
        // The long form the JVM prints parses too.
        parseHostPort("[fd00:0:0:0:0:0:0:1]:47420") shouldBe HostPort("fd00:0:0:0:0:0:0:1", 47_420)
    }

    @Test
    fun ipv4TailOfIpv6IsAccepted() {
        parseHostPort("[::ffff:192.168.1.20]:47420") shouldBe HostPort("::ffff:192.168.1.20", 47_420)
    }

    @Test
    fun scopedLinkLocalIsRejected() {
        parseHostPort("[fe80::1%wlan0]:1").shouldBeNull()
        parseHostPort("fe80::1%wlan0").shouldBeNull()
    }

    @Test
    fun namesAndNonsenseAreRejected() {
        listOf(
            "",
            "nohost",
            ":47420",
            "192.168.1.20:",
            "example.com:443",
            "evil.lan:1",
            "deadbeef:1",
            "300.1.1.1:5",
            "1.2.3:5",
            "[1:2:3:4:5:6:7:8:9]:5",
            "a::b::c:5",
            "[1.2.3.4::]:5",
            "[fe80::1%]:5",
            "[fe80::1%wlan0%x]:5",
            "fe80::1%wlan0",
            "[fe80::1%wlan0]:5000",
        ).forEach { parseHostPort(it).shouldBeNull() }
    }

    @Test
    fun portRangeIsChecked() {
        parseHostPort("192.168.1.20:0").shouldBeNull()
        parseHostPort("192.168.1.20:65536").shouldBeNull()
        parseHostPort("192.168.1.20:1") shouldBe HostPort("192.168.1.20", 1)
        parseHostPort("192.168.1.20:65535") shouldBe HostPort("192.168.1.20", 65_535)
    }
}
