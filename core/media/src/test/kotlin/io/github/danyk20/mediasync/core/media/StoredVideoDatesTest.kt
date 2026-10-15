/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import io.github.danyk20.mediasync.core.metadata.VideoDates
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.RandomAccessFile
import java.time.ZoneId
import java.time.ZonedDateTime
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Test

/** Received videos without a capture time get one, in place (docs/PLAN.md §6.2, point 3). */
class StoredVideoDatesTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val files = mutableListOf<File>()

    @After
    fun tearDown() = files.forEach { it.delete() }

    @Test
    fun aVideoWithoutATimeGetsTheGalleryDate() {
        val stored = store(undated())
        val gallery = millis("2026-10-14T07:30:00Z")

        complete(stored, descriptor("VID_1.mp4", gallery = gallery)) shouldBe gallery

        stored.length() shouldBe undated().size.toLong()
        capturedAt(stored) shouldBe gallery
    }

    @Test
    fun aWhatsAppVideoGetsTheDayInItsName() {
        val stored = store(undated())

        val written = complete(stored, descriptor("VID-20250512-WA0001.mp4"))

        written shouldBe millis("2025-05-12T12:00+02:00[Europe/Zurich]")
        capturedAt(stored) shouldBe written
    }

    @Test
    fun aVideoWithATimeIsLeftAsItIs() {
        val original = golden()
        val stored = store(original)

        complete(stored, descriptor("VID-20250512-WA0001.mp4", gallery = 1_000)).shouldBeNull()

        stored.readBytes().toList() shouldBe original.toList()
    }

    @Test
    fun withNothingToGoByNothingIsWritten() {
        val stored = store(undated())

        complete(stored, descriptor("clip.mp4")).shouldBeNull()

        capturedAt(stored).shouldBeNull()
    }

    private fun complete(file: File, descriptor: MediaDescriptor): Long? = RandomAccessFile(file, "rw").use { access ->
        StoredVideoDates.complete(access.channel, access.channel, descriptor, zurich)
    }

    private fun capturedAt(file: File): Long? {
        val bytes = file.readBytes()
        return VideoDates.capturedAtMillis(bytes.size.toLong()) { offset, count ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
    }

    private fun descriptor(name: String, gallery: Long? = null) = MediaDescriptor(
        fingerprint = name.encodeUtf8().sha256(),
        size = 0,
        mimeType = "video/mp4",
        displayName = name,
        meta = MediaMeta(capturedAtMillis = gallery),
    )

    private fun store(bytes: ByteArray): File = File.createTempFile("received", ".mp4").also {
        it.writeBytes(bytes)
        files += it
    }

    private fun golden(): ByteArray = javaClass.getResourceAsStream("/golden/video.mp4")!!.use { it.readBytes() }

    /** The golden video with the creation and modification times of its movie, tracks and media set to zero. */
    private fun undated(): ByteArray = golden().also { video ->
        val text = String(video, Charsets.ISO_8859_1)
        listOf("mvhd", "tkhd", "mdhd").forEach { type ->
            var at = text.indexOf(type)
            while (at >= 0) {
                val payload = at + 4
                val width = if (video[payload].toInt() == 0) 8 else 16
                ByteArray(width).copyInto(video, payload + 4)
                at = text.indexOf(type, at + 1)
            }
        }
    }

    private fun millis(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()
}
