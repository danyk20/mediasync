/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.engine.Fingerprints.SAMPLE_BYTES
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/** Fingerprints pinned from the code before it was made common: the wire format must not change on any platform. */
class FingerprintsGoldenTest {

    private fun fingerprintOf(size: Int): String = Fingerprints.of(Random(7).nextBytes(size)).hex()

    @Test
    fun fingerprintsOfTheShippedFormatAreUnchanged() {
        fingerprintOf(0) shouldBe "388cdea9081dd1d5efbd69cc28c97f7790c3791dab9b626a3703d0d0ba30b4f3"
        fingerprintOf(1) shouldBe "0d5a6f8a29b1156a4944246c508b95950b14cee5a5716879570334a59a29d956"
        fingerprintOf(2 * SAMPLE_BYTES) shouldBe "62c6261b27254960ae8efd324cabacd5c6fb20349c8cdccdc64f39b2d088dc36"
        fingerprintOf(2 * SAMPLE_BYTES + 1) shouldBe "69c5b297d8d514089d97c5ee7ec91516ded250d35fdb8f57a1278f920f515461"
        fingerprintOf(3 * SAMPLE_BYTES) shouldBe "8d7cf890e56480fa55170651605d0324d3294cd283ff57b98c1034ec0f177249"
    }
}
