/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

class PairingLinkTest {

    private val link = PairingLink(
        consumer = deviceId(2),
        token = ByteArray(16) { it.toByte() }.toByteString(),
        addresses = listOf("192.168.1.20:47420", "[fd00::1]:47420"),
        consumerName = "Daniel's Pixel 2 XL",
    )

    @Test
    fun `formats as a mediasync link`() {
        link.toString() shouldStartWith "mediasync://pair?v=1&id=${deviceId(2).hex}&t=AAECAwQFBgcICQoLDA0ODw&a="
    }

    @Test
    fun `round-trips through text`() {
        PairingLink.parse(link.toString()) shouldBe link
    }

    @Test
    fun `tolerates surrounding whitespace, like a pasted link`() {
        PairingLink.parse("  ${link}\n") shouldBe link
    }

    @Test
    fun `any name and addresses survive encoding`() = runBlocking {
        checkAll(Arb.string(0..64), Arb.list(Arb.string(1..30), 1..4)) { name, addresses ->
            val custom = link.copy(
                consumerName = name,
                addresses = addresses.filter {
                    it.isNotBlank()
                }.ifEmpty { listOf("h:1") },
            )
            PairingLink.parse(custom.toString()) shouldBe custom
        }
        Unit
    }

    @Test
    fun `rejects anything else`() {
        listOf(
            "",
            "https://example.com",
            "mediasync://pair?v=2&id=${deviceId(2).hex}&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=x",
            "mediasync://pair?v=1&id=nothex&t=AAECAwQFBgcICQoLDA0ODw&a=h:1&n=x",
            "mediasync://pair?v=1&id=${deviceId(2).hex}&t=short&a=h:1&n=x",
            "mediasync://pair?v=1&id=${deviceId(2).hex}&t=AAECAwQFBgcICQoLDA0ODw&a=&n=x",
            "mediasync://pair?v=1&id=${deviceId(2).hex}&t=AAECAwQFBgcICQoLDA0ODw&n=x",
        ).forEach { PairingLink.parse(it).shouldBeNull() }
    }
}
