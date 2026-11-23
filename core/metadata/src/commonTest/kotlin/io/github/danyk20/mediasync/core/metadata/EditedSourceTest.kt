/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.IOException
import okio.buffer
import okio.use

class EditedSourceTest {
    /** Random files with random sorted, non-overlapping edits. */
    private val edited = arbitrary {
        val size = Arb.int(0..5_000).bind()
        val file = Arb.byteArray(Arb.constant(size), Arb.byte()).bind()
        val edits = mutableListOf<Edit>()
        var at = 0L
        while (at < size && edits.size < 20) {
            at += Arb.long(0L..size / 4L + 1).bind()
            if (at > size) break
            val length = Arb.long(0L..minOf(300L, size - at)).bind()
            val replacement = ByteArray(Arb.int(0..300).bind()) { (it * 7).toByte() }
            if (length > 0 || replacement.isNotEmpty()) edits += Edit(at, length, replacement)
            at += length + 1
        }
        file to edits
    }

    @Test
    fun noEditsStreamTheOriginalUnchanged() {
        val bytes = ByteArray(10) { it.toByte() }
        Redaction.Edited(emptyList(), 10).open(Buffer().write(bytes)).buffer().readByteArray().toList() shouldBe
            bytes.toList()
    }

    @Test
    fun editsStreamExactlyAsTheyApplyInMemory() = runTest {
        checkAll(edited) { (file, edits) ->
            val redaction = Redaction.Edited(edits, file.size.toLong())

            val streamed = redaction.open(Buffer().write(file)).buffer().use { it.readByteArray() }

            streamed.toList() shouldBe reference(file, edits).toList()
            streamed.size.toLong() shouldBe redaction.outputSize
        }
    }

    @Test
    fun aResumedTransferSkipsToTheSameBytes() = runTest {
        checkAll(edited, Arb.int(0..6_000)) { (file, edits), skip ->
            val whole = reference(file, edits)
            val from = minOf(skip, whole.size)
            val source = Redaction.Edited(edits, file.size.toLong()).open(Buffer().write(file)).buffer()

            source.skip(from.toLong())
            val rest = Buffer()
            while (!source.exhausted()) rest.writeByte(source.readByte().toInt())

            rest.readByteArray().toList() shouldBe whole.copyOfRange(from, whole.size).toList()
        }
    }

    @Test
    fun aFileThatEndsInsideAnEditFailsAsAnIoError() {
        val source = Redaction.Edited(listOf(Edit(5, 10)), 20).open(Buffer().write(ByteArray(8))).buffer()

        val error = shouldThrow<IOException> { source.readByteArray() }

        error.message shouldBe "The file ended inside an edit"
    }

    private fun reference(file: ByteArray, edits: List<Edit>): ByteArray {
        val out = Buffer()
        var at = 0
        for (edit in edits) {
            out.write(file, at, edit.offset.toInt() - at)
            out.write(edit.bytes)
            at = edit.end.toInt()
        }
        out.write(file, at, file.size - at)
        return out.readByteArray()
    }
}
