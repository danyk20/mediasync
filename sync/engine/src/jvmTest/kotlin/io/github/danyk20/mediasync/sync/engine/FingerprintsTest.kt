/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.engine.Fingerprints.SAMPLE_BYTES
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

class FingerprintsTest {

    private val large = Random(7).nextBytes(3 * SAMPLE_BYTES)

    @Test
    fun `identical content has identical fingerprints`() {
        Fingerprints.of(large) shouldBe Fingerprints.of(large.copyOf())
    }

    @Test
    fun `changing the size changes the fingerprint`() {
        Fingerprints.of(large) shouldNotBe Fingerprints.of(large.copyOf(large.size + 1))
    }

    @Test
    fun `changing the head or the tail changes the fingerprint`() {
        Fingerprints.of(large.flipped(10)) shouldNotBe Fingerprints.of(large)
        Fingerprints.of(large.flipped(large.size - 1)) shouldNotBe Fingerprints.of(large)
    }

    @Test
    fun `small files are fingerprinted completely`() {
        val small = Random(3).nextBytes(2 * SAMPLE_BYTES)

        Fingerprints.of(small.flipped(SAMPLE_BYTES)) shouldNotBe Fingerprints.of(small)
    }

    @Test
    fun `large files are sampled, so a change only in the middle isn't seen (full hash catches it)`() {
        Fingerprints.of(large.flipped(large.size / 2)) shouldBe Fingerprints.of(large)
    }

    @Test
    fun `an empty file has a fingerprint`() {
        Fingerprints.of(ByteArray(0)).size shouldBe 32
    }

    @Test
    fun `reads only the two samples of a large file`() {
        val reads = mutableListOf<Pair<Long, Int>>()

        Fingerprints.of(large.size.toLong()) { offset, count ->
            reads += offset to count
            large.copyOfRange(offset.toInt(), offset.toInt() + count)
        }

        reads shouldBe listOf(0L to SAMPLE_BYTES, (large.size - SAMPLE_BYTES).toLong() to SAMPLE_BYTES)
    }

    private fun ByteArray.flipped(index: Int) = copyOf().also { it[index] = it[index].toInt().inv().toByte() }
}
