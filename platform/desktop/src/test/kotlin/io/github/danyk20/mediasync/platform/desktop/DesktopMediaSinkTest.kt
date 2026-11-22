/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopMediaSinkTest {

    @TempDir
    lateinit var root: Path

    private val producer = DeviceId("ab".repeat(32))
    private val inbox get() = root.resolve("inbox")
    private fun sink(reserve: Long = 0) = DesktopMediaSink(inbox, reserveBytes = reserve, zone = ZoneOffset.UTC)

    private fun descriptor(
        content: ByteArray,
        name: String = "IMG_1.jpg",
        mime: String = "image/jpeg",
        meta: MediaMeta = MediaMeta(),
    ) = MediaDescriptor(content.toByteString().sha256(), content.size.toLong(), mime, name, meta)

    private val bytes = ByteArray(10_000) { (it % 251).toByte() }

    private fun inboxFiles() = Files.list(inbox).use { it.map { p -> p.fileName.toString() }.sorted().toList() }

    @Test
    fun `a received file lands in the inbox with exactly its bytes`() {
        val sink = sink()
        val d = descriptor(bytes)
        val file = sink.open(producer, d, 0)
        file.write(bytes, 0, 4_000)
        file.write(bytes, 4_000, 6_000)

        val location = file.commit()
        file.close()

        inboxFiles() shouldBe listOf("IMG_1.jpg")
        Files.readAllBytes(inbox.resolve("IMG_1.jpg")).toList() shouldBe bytes.toList()
        location shouldBe inbox.resolve("IMG_1.jpg").toString()
        sink.partialSize(producer, d.fingerprint) shouldBe 0
    }

    @Test
    fun `the inbox shows nothing until the file is committed`() {
        val sink = sink()
        val file = sink.open(producer, descriptor(bytes), 0)
        file.write(bytes, 0, 100)

        inboxFiles() shouldBe emptyList()
        file.close()
    }

    @Test
    fun `an interrupted transfer is kept and resumed, also by a new sink after a restart`() {
        val d = descriptor(bytes)
        sink().open(producer, d, 0).apply {
            write(bytes, 0, 3_000)
            close() // The connection broke: no commit.
        }

        val after = sink() // A restart.
        after.partialSize(producer, d.fingerprint) shouldBe 3_000
        val resumed = after.open(producer, d, 3_000)
        resumed.existingBytes().buffer().use { it.readByteArray().toList() shouldBe bytes.take(3_000) }
        resumed.write(bytes, 3_000, 7_000)
        resumed.commit()
        resumed.close()

        Files.readAllBytes(inbox.resolve("IMG_1.jpg")).toList() shouldBe bytes.toList()
        after.partialSize(producer, d.fingerprint) shouldBe 0
    }

    @Test
    fun `resuming drops bytes the sender did not confirm, and cannot go past what was kept`() {
        val d = descriptor(bytes)
        val sink = sink()
        sink.open(producer, d, 0).apply {
            write(bytes, 0, 3_000)
            close()
        }

        sink.open(producer, d, 1_000).apply { close() }
        sink.partialSize(producer, d.fingerprint) shouldBe 1_000
        shouldThrow<IOException> { sink.open(producer, d, 2_000) }
        sink.open(producer, d, 0).close()
        sink.partialSize(producer, d.fingerprint) shouldBe 0
    }

    @Test
    fun `discard removes the partial file`() {
        val d = descriptor(bytes)
        val sink = sink()
        val file = sink.open(producer, d, 0)
        file.write(bytes, 0, 500)

        file.discard()

        sink.partialSize(producer, d.fingerprint) shouldBe 0
        inboxFiles() shouldBe emptyList()
    }

    @Test
    fun `partial files of two producers do not mix`() {
        val other = DeviceId("cd".repeat(32))
        val d = descriptor(bytes)
        val sink = sink()
        sink.open(producer, d, 0).apply {
            write(bytes, 0, 10)
            close()
        }

        sink.partialSize(other, d.fingerprint) shouldBe 0
    }

    @Test
    fun `a second file with the same name gets a number, not the first one's place`() {
        val sink = sink()
        val second = ByteArray(50) { 7 }
        for ((content, _) in listOf(bytes to 0, second to 1)) {
            val file = sink.open(producer, descriptor(content), 0)
            file.write(content, 0, content.size)
            file.commit()
            file.close()
        }

        inboxFiles() shouldBe listOf("IMG_1 (1).jpg", "IMG_1.jpg")
        Files.readAllBytes(inbox.resolve("IMG_1.jpg")).size shouldBe bytes.size
        Files.readAllBytes(inbox.resolve("IMG_1 (1).jpg")).size shouldBe 50
    }

    @Test
    fun `the file keeps the producer's modification time`() {
        val modified = 1_600_000_000_000L
        val d = descriptor(bytes, meta = MediaMeta(capturedAtMillis = 1_500_000_000_000L, modifiedAtMillis = modified))
        val file = sink().open(producer, d, 0)
        file.write(bytes, 0, bytes.size)
        file.commit()
        file.close()

        Files.getLastModifiedTime(inbox.resolve("IMG_1.jpg")).toMillis() shouldBe modified
    }

    @Test
    fun `without a modification time the capture date is used`() {
        val captured = 1_500_000_000_000L
        val d = descriptor(bytes, meta = MediaMeta(capturedAtMillis = captured))
        val file = sink().open(producer, d, 0)
        file.write(bytes, 0, bytes.size)
        file.commit()
        file.close()

        Files.getLastModifiedTime(inbox.resolve("IMG_1.jpg")).toMillis() shouldBe captured
    }

    @Test
    fun `available bytes keep the reserve free, and never go below zero`() {
        val free = Files.getFileStore(root).usableSpace
        (sink(reserve = 0).availableBytes() > 0) shouldBe true
        sink(reserve = free + 1_000_000_000_000L).availableBytes() shouldBe 0
    }

    @Test
    fun `names are made safe on every operating system`() {
        safeFileName("a/b\\c.jpg") shouldBe "c.jpg"
        safeFileName("../../etc/passwd") shouldBe "passwd"
        safeFileName("we<ir>d:na\"me|?*.mp4") shouldBe "we_ir_d_na_me___.mp4"
        safeFileName("  .hidden. ") shouldBe "hidden"
        safeFileName("") shouldBe "file"
        safeFileName("...") shouldBe "file"
        safeFileName("CON.txt") shouldBe "_CON.txt"
        safeFileName("lpt1") shouldBe "_lpt1"
        safeFileName("20251018_101530.LS.mp4") shouldBe "20251018_101530.LS.mp4"
        val long = safeFileName("x".repeat(300) + ".jpeg")
        long.length shouldBe 200
        long.endsWith(".jpeg") shouldBe true
        safeFileName("y".repeat(300)).length shouldBe 200
    }

    @Test
    fun `a video without a capture date gets one in its movie header, and stays the same size`() {
        val video = movieWithoutDate()
        val d = descriptor(
            video,
            name = "VID_20240101_101010.mp4",
            mime = "video/mp4",
            meta = MediaMeta(capturedAtMillis = 1_704_103_810_000L),
        )
        val file = sink().open(producer, d, 0)
        file.write(video, 0, video.size)
        file.commit()
        file.close()

        val stored = Files.readAllBytes(inbox.resolve("VID_20240101_101010.mp4"))
        stored.size shouldBe video.size
        (stored.toList() == video.toList()) shouldBe false
    }

    /** A minimal ftyp + moov(mvhd v0, creation 0): the smallest thing core:metadata's VideoDates accepts. */
    private fun movieWithoutDate(): ByteArray {
        val out = Buffer()
        out.writeInt(24).writeUtf8("ftyp").writeUtf8("isom").writeInt(0).writeUtf8("isom").writeUtf8("mp41")
        val mvhd = Buffer().writeInt(108).writeUtf8("mvhd").write(ByteArray(100))
        out.writeInt(8 + 108).writeUtf8("moov").writeAll(mvhd)
        return out.readByteArray()
    }
}
