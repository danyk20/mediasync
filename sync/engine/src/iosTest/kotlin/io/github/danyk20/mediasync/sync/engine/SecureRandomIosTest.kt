/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SecureRandomIosTest {

    @Test
    fun nextIntOfOneIsAlwaysZero() {
        val random = secureRandom()
        repeat(100) { random.nextInt(1) shouldBe 0 }
    }
}
