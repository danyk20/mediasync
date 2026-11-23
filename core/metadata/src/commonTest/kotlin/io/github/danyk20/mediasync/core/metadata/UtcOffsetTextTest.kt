/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UtcOffsetTextTest {
    @Test
    fun theFormatsOfZoneOffsetOfAreRead() {
        val expected = mapOf(
            "+02:00" to 7200, "-05:30" to -19800, "Z" to 0, "+02" to 7200, "+2" to 7200, "+0200" to 7200,
            "+02:00:30" to 7230, "+020030" to 7230, "-00:00" to 0,
        )
        for ((text, seconds) in expected) parseUtcOffsetSeconds(text) shouldBe seconds
    }

    @Test
    fun otherTextsGiveNone() {
        for (text in listOf("", "02:00", "+2:00", "+02:0", "+02;00", "z", "+02:00:")) {
            parseUtcOffsetSeconds(text) shouldBe null
        }
    }

    @Test
    fun theLimitIs18Hours() {
        parseUtcOffsetSeconds("+18:00") shouldBe 64800
        parseUtcOffsetSeconds("-18:00") shouldBe -64800
        parseUtcOffsetSeconds("+18:01") shouldBe null
        parseUtcOffsetSeconds("+19:00") shouldBe null
        parseUtcOffsetSeconds("+02:60") shouldBe null
    }
}
