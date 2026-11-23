/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import okio.Buffer
import okio.buffer

class InputStreamAdapterTest {
    @Test
    fun theInputStreamAdapterGivesTheSameBytes() {
        val original = GoldenMediaTest.golden("pixel.jpg")
        val plan = PhotoDetailsRemover.plan(original.size.toLong(), original.asReader(), PhotoDetails.REMOVE_LOCATION)
            as Redaction.Edited

        val viaStream = plan.open(original.inputStream()).use { it.readBytes() }
        val viaSource = plan.open(Buffer().write(original)).buffer().readByteArray()

        viaStream.toList() shouldBe viaSource.toList()
    }
}
