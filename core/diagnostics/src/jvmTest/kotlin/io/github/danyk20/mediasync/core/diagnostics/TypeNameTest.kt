/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.jupiter.api.Test

class TypeNameTest {

    @Test
    fun describeNamesEachErrorTypeOnTheJvm() {
        Diagnostics.describe(IOException("x", SocketTimeoutException("y"))) shouldBe
            "IOException: x ← SocketTimeoutException: y"
    }
}
