/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import okio.ByteString.Companion.toByteString

/** Links pinned from the code before it was made common: the QR text must not change on any platform. */
class PairingLinkGoldenTest {

    private val id = DeviceId.of(ByteArray(32) { it.toByte() })
    private val prefix = "mediasync://pair?v=1&id=${id.hex}"
    private val token = ByteArray(16) { it.toByte() }.toByteString()
    private val ones = ByteArray(16) { -1 }.toByteString()
    private val one = listOf("192.168.1.20:47420")
    private val two = listOf("192.168.1.20:47420", "[fd00::1]:47420")

    private fun pinned(text: String, link: PairingLink) {
        link.toString() shouldBe text
        PairingLink.parse(text) shouldBe link
    }

    @Test
    fun namesOfEveryKindEncodeAsBefore() {
        pinned("$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420&n=Pixel", PairingLink(id, token, one, "Pixel"))
        pinned(
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420,%5Bfd00%3A%3A1%5D%3A47420&n=Daniel%27s+Pixel+2+XL",
            PairingLink(id, token, two, "Daniel's Pixel 2 XL"),
        )
        pinned(
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420&n=a+b%2Bc%25d*e%7Ef%26g%3Dh%2Fi",
            PairingLink(id, token, one, "a b+c%d*e~f&g=h/i"),
        )
        pinned("$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420&n=%C3%BC", PairingLink(id, token, one, "ü"))
        pinned(
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420&n=%E6%97%A5%E6%9C%AC%E8%AA%9E",
            PairingLink(id, token, one, "日本語"),
        )
        pinned(
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20%3A47420&n=x%F0%9F%98%80y",
            PairingLink(id, token, one, "x😀y"),
        )
        pinned("$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=h%3A1+2&n=", PairingLink(id, token, listOf("h:1 2"), ""))
    }

    @Test
    fun tokensEncodeWithoutPaddingAsBefore() {
        pinned(
            "$prefix&t=_____________________w&a=192.168.1.20%3A47420&n=a.b-c_d",
            PairingLink(id, ones, one, "a.b-c_d"),
        )
    }

    @Test
    fun aPaddedTokenIsStillAccepted() {
        PairingLink.parse("$prefix&t=AAECAwQFBgcICQoLDA0ODw==&a=h:1&n=x").shouldNotBeNull()
    }

    @Test
    fun malformedLinksAreRejectedAsBefore() {
        listOf(
            "",
            "https://example.com",
            "mediasync://pair?v=2&id=${id.hex}&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=x",
            "mediasync://pair?v=1&id=nothex&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=x",
            "$prefix&t=short&a=h:1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0O+w&a=h:1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0O/w&a=h:1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw=&a=h:1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODwA&a=h:1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODwAAA&a=h:1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=h%zz&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=h%1&n=x",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=x%",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=%-F",
            "$prefix&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=%F",
        ).forEach { PairingLink.parse(it).shouldBeNull() }
    }
}
