/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith

class Sha256IosTest {

    @Test
    fun outOfRangeOffsetOrCountIsRejected() {
        val bytes = ByteArray(8)
        assertFailsWith<IllegalArgumentException> { newSha256().update(bytes, offset = -1, byteCount = 1) }
        assertFailsWith<IllegalArgumentException> { newSha256().update(bytes, offset = 0, byteCount = -1) }
        assertFailsWith<IllegalArgumentException> { newSha256().update(bytes, offset = 0, byteCount = 9) }
        assertFailsWith<IllegalArgumentException> { newSha256().update(bytes, offset = 4, byteCount = 5) }
    }

    @Test
    fun updateAfterDigestFailsFast() {
        val sha = newSha256()
        sha.update("abc".encodeToByteArray())
        sha.digest()
        assertFailsWith<IllegalStateException> { sha.update("def".encodeToByteArray()) }
    }

    @Test
    fun digestTwiceFailsFast() {
        val sha = newSha256()
        sha.digest()
        assertFailsWith<IllegalStateException> { sha.digest() }
    }
}
