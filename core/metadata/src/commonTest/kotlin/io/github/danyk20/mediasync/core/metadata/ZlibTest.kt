/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ZlibTest {
    @Test
    fun dataRoundTrips() {
        val data = ByteArray(5_000) { (it % 13).toByte() }
        Zlib.inflate(Zlib.deflate(data)).toList() shouldBe data.toList()
    }

    @Test
    fun aKnownStreamInflatesAndDeflatesToTheSameBytes() {
        val stream = "789ccb48cdc9c957c8402701680308b1".hexBytes()
        Zlib.inflate(stream).decodeToString() shouldBe "hello hello hello hello"
        Zlib.deflate("hello hello hello hello".encodeToByteArray()).hex() shouldBe "789ccb48cdc9c957c8402701680308b1"
    }

    @Test
    fun emptyInputRoundTrips() {
        Zlib.inflate(Zlib.deflate(ByteArray(0))).toList() shouldBe emptyList<Byte>()
    }

    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
