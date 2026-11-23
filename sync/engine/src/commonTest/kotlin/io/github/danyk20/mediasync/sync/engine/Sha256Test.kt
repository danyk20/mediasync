/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test
import okio.ByteString.Companion.toByteString

class Sha256Test {

    private fun digestOf(vararg parts: ByteArray): String {
        val sha = newSha256()
        parts.forEach { sha.update(it) }
        return sha.digest().hex()
    }

    @Test
    fun emptyInputMatchesTheStandardVector() {
        digestOf(ByteArray(0)) shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @Test
    fun abcMatchesTheStandardVector() {
        digestOf("abc".encodeToByteArray()) shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun aTwoBlockMessageMatchesTheStandardVector() {
        digestOf("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()) shouldBe
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    }

    @Test
    fun aMillionAsMatchTheStandardVector() {
        val sha = newSha256()
        val block = ByteArray(1_000) { 'a'.code.toByte() }
        repeat(1_000) { sha.update(block) }
        sha.digest().hex() shouldBe "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    }

    @Test
    fun anyChunksAtAnyOffsetsMatchTheReferenceHash() {
        val chunkSizes = listOf(1, 63, 64, 65, 4_096)
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 1_000_003)) {
            val data = Random(42).nextBytes(size)
            val expected = data.toByteString().sha256()
            for (chunk in chunkSizes + size) {
                if (chunk == 1 && size > 1_000) continue // One call per byte: too slow for the big input.
                val offset = 5
                val buffer = ByteArray(offset + size + 3).also { data.copyInto(it, offset) }
                val sha = newSha256()
                var pos = 0
                while (pos < size) {
                    val count = minOf(chunk, size - pos)
                    sha.update(buffer, offset + pos, count)
                    pos += count
                }
                sha.digest() shouldBe expected
            }
        }
    }

    @Test
    fun emptyUpdatesDoNotChangeTheDigest() {
        val sha = newSha256()
        sha.update(ByteArray(0))
        sha.update(ByteArray(8), offset = 4, byteCount = 0)
        sha.digest() shouldBe ByteArray(0).toByteString().sha256()
    }
}
