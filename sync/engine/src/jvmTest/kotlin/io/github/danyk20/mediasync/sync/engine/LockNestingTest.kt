/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LockNestingTest {

    @Test
    fun aNestedLockFailsInsteadOfDeadlocking() {
        val lock = Lock()

        shouldThrow<IllegalStateException> { lock.withLock { lock.withLock { 1 } } }

        lock.withLock { 2 } shouldBe 2
    }
}
