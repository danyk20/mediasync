/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import okio.Buffer
import okio.IOException
import okio.buffer

class EditedSourceTest {

    @Test
    fun noEditsStreamTheOriginalUnchanged() {
        val bytes = ByteArray(10) { it.toByte() }
        Redaction.Edited(emptyList(), 10).open(Buffer().write(bytes)).buffer().readByteArray().toList() shouldBe
            bytes.toList()
    }

    @Test
    fun aFileThatEndsInsideAnEditFailsAsAnIoError() {
        val source = Redaction.Edited(listOf(Edit(5, 10)), 20).open(Buffer().write(ByteArray(8))).buffer()

        val error = shouldThrow<IOException> { source.readByteArray() }

        error.message shouldBe "The file ended inside an edit"
    }
}
