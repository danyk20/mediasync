/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.core.model.SendRange
import io.github.danyk20.mediasync.sync.engine.Fingerprints
import io.github.danyk20.mediasync.sync.engine.SourceItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.datetime.LocalDate
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A computer's folders as a source of photos and videos, on temporary folders only. */
class FolderMediaSourceTest {

    @TempDir
    lateinit var root: Path

    private val now = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
    private val longAgo = Instant.parse("2026-02-02T08:00:00Z")
    private var folders: Set<Path> = emptySet()
    private var range = SendRange.Everything

    private val source = FolderMediaSource(
        folders = { folders },
        range = { range },
        zone = { ZoneOffset.UTC },
        clock = { now },
        settleMillis = 5_000,
    )

    private fun golden(name: String): ByteArray =
        FolderMediaSourceTest::class.java.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }

    private fun put(relative: String, bytes: ByteArray, modified: Instant = longAgo, within: Path = root): Path =
        within.resolve(relative).also {
            Files.createDirectories(it.parent)
            Files.write(it, bytes)
            Files.setLastModifiedTime(it, FileTime.from(modified))
        }

    private fun names() = source.items().map { it.descriptor.displayName }

    private fun list(): Map<String, SourceItem> = source.items().associateBy { it.descriptor.displayName }

    @Test
    fun `photos and videos below a chosen folder are listed with their type`() {
        put("a.jpg", golden("pixel.jpg"))
        put("trips/b.PNG", golden("screenshot.png"))
        put("trips/2026/c.mp4", golden("video.mp4"))
        put("trips/d.heic", golden("photo.heic"))
        put("e.dng", golden("raw.dng"))
        folders = setOf(root)

        list().mapValues { it.value.descriptor.mimeType } shouldBe mapOf(
            "a.jpg" to "image/jpeg",
            "b.PNG" to "image/png",
            "c.mp4" to "video/mp4",
            "d.heic" to "image/heic",
            "e.dng" to "image/x-adobe-dng",
        )
    }

    @Test
    fun `nothing is listed for no folder, a missing folder or a file`() {
        put("a.jpg", golden("pixel.jpg"))

        names().shouldBeEmpty()
        folders = setOf(root.resolve("missing"), root.resolve("a.jpg"))
        names().shouldBeEmpty()
    }

    @Test
    fun `a change of the chosen folders applies to the next listing`() {
        put("one/a.jpg", golden("pixel.jpg"))
        put("two/b.jpg", golden("samsung.jpg"))

        folders = setOf(root.resolve("one"))
        names() shouldBe listOf("a.jpg")
        folders = setOf(root.resolve("two"))
        names() shouldBe listOf("b.jpg")
    }

    @Test
    fun `files that are not what their name says are left out`() {
        put("fake.jpg", "this is not a photo".toByteArray())
        put("note.txt", golden("pixel.jpg"))
        put("noextension", golden("pixel.jpg"))
        put("empty.jpg", ByteArray(0))
        put("png-called-jpg.jpg", golden("screenshot.png"))
        put("real.jpg", golden("pixel.jpg"))
        folders = setOf(root)

        names() shouldBe listOf("real.jpg")
    }

    @Test
    fun `hidden files, hidden folders, photo libraries and links are left out`() {
        put(".hidden.jpg", golden("pixel.jpg"))
        put("._appledouble.jpg", golden("pixel.jpg"))
        put(".thumbnails/t.jpg", golden("pixel.jpg"))
        put("Photos Library.photoslibrary/originals/p.jpg", golden("pixel.jpg"))
        val outside = Files.createDirectories(root.resolveSibling("${root.fileName}-outside"))
        put("outside.jpg", golden("pixel.jpg"), within = outside)
        val chosen = Files.createDirectories(root.resolve("chosen"))
        put("shown.jpg", golden("samsung.jpg"), within = chosen)
        symlinkOrSkip(chosen.resolve("link.jpg"), outside.resolve("outside.jpg"))
        symlinkOrSkip(chosen.resolve("linked-folder"), outside)
        folders = setOf(chosen)

        names() shouldBe listOf("shown.jpg")
    }

    private fun symlinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            // A file system without links (some Windows set-ups): nothing to leave out.
        } catch (_: IOException) {
            // As above.
        }
    }

    @Test
    fun `a folder chosen twice, or inside another chosen one, lists its files once`() {
        put("all/a.jpg", golden("pixel.jpg"))
        put("all/inner/b.jpg", golden("samsung.jpg"))
        folders = setOf(root.resolve("all"), root.resolve("all/inner"), root.resolve("all/../all"))

        names() shouldContainExactlyInAnyOrder listOf("a.jpg", "b.jpg")
    }

    @Test
    fun `the fingerprint is the engine's, so a renamed or moved file is the same photo`() {
        val bytes = golden("pixel.jpg")
        val file = put("a.jpg", bytes)
        folders = setOf(root)
        val before = source.items().single()

        before.descriptor.fingerprint shouldBe Fingerprints.of(bytes)
        before.descriptor.size shouldBe bytes.size.toLong()
        before.key shouldBe file.toString()

        val moved = root.resolve("moved/b.jpg").also { Files.createDirectories(it.parent) }
        Files.move(file, moved)
        val after = source.items().single()

        after.descriptor.fingerprint shouldBe before.descriptor.fingerprint
        after.key shouldBe moved.toString()
    }

    @Test
    fun `the date inside a photo is sent with its offset, the album is the folder`() {
        put("Holiday/a.jpg", golden("pixel.jpg"), modified = longAgo)
        folders = setOf(root)

        val meta = source.items().single().descriptor.meta

        meta.capturedAtMillis shouldBe Instant.parse("2026-10-14T07:30:00Z").toEpochMilli()
        meta.utcOffsetMinutes shouldBe 120
        meta.album shouldBe "Holiday"
        meta.modifiedAtMillis shouldBe longAgo.toEpochMilli()
        meta.favorite shouldBe false
    }

    @Test
    fun `a video's date is sent without an offset it never had`() {
        put("v.mov", golden("video.mov"))
        folders = setOf(root)

        val meta = source.items().single().descriptor.meta

        meta.capturedAtMillis shouldBe Instant.parse("2026-10-14T07:30:00Z").toEpochMilli()
        meta.utcOffsetMinutes.shouldBeNull()
    }

    @Test
    fun `a file without a date inside travels with its modification time only`() {
        put("IMG-20250512-WA0003.jpg", golden("whatsapp.jpg"), modified = longAgo)
        folders = setOf(root)

        val meta = source.items().single().descriptor.meta

        meta.capturedAtMillis.shouldBeNull()
        meta.utcOffsetMinutes.shouldBeNull()
        meta.modifiedAtMillis shouldBe longAgo.toEpochMilli()
    }

    @Test
    fun `the range counts the date inside, else the one in the name, else the modification time`() {
        put("inside.jpg", golden("pixel.jpg"), modified = longAgo) // 14 October (inside)
        val named = Instant.parse("2026-11-01T00:00:00Z")
        put("IMG_20260101_120000.jpg", golden("whatsapp.jpg"), modified = named) // 1 Jan
        put("changed.png", golden("screenshot.png"), modified = Instant.parse("2026-03-01T10:00:00Z")) // 1 March
        folders = setOf(root)

        range = SendRange(from = LocalDate(2026, 3, 1), until = LocalDate(2026, 10, 13))
        names() shouldBe listOf("changed.png")

        range = SendRange(from = LocalDate(2026, 10, 14), until = LocalDate(2026, 10, 14)) // Both days included.
        names() shouldBe listOf("inside.jpg")

        range = SendRange(until = LocalDate(2026, 2, 28))
        names() shouldBe listOf("IMG_20260101_120000.jpg")

        range = SendRange(from = LocalDate(2026, 3, 1))
        names() shouldContainExactlyInAnyOrder listOf("inside.jpg", "changed.png")

        range = SendRange.Everything
        names().size shouldBe 3
    }

    @Test
    fun `a file changed a moment ago is left for the next listing`() {
        put("done.jpg", golden("pixel.jpg"), modified = Instant.ofEpochMilli(now - 5_000))
        put("copying.jpg", golden("samsung.jpg"), modified = Instant.ofEpochMilli(now - 4_999))
        folders = setOf(root)

        names() shouldBe listOf("done.jpg")
    }

    @Test
    fun `a file dated in the future is listed`() {
        put("future.jpg", golden("pixel.jpg"), modified = Instant.ofEpochMilli(now + 86_400_000))
        folders = setOf(root)

        names() shouldBe listOf("future.jpg")
    }

    @Test
    fun `what was read is kept until the file changes`() {
        val bytes = golden("pixel.jpg")
        val file = put("a.jpg", bytes)
        folders = setOf(root)
        val first = source.items().single().descriptor.fingerprint

        // Different bytes of the same size with the same modification time: only a cache can tell.
        val other = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        Files.write(file, other)
        Files.setLastModifiedTime(file, FileTime.from(longAgo))
        source.items().single().descriptor.fingerprint shouldBe first

        Files.setLastModifiedTime(file, FileTime.from(longAgo.plusSeconds(60)))
        source.items().single().descriptor.fingerprint shouldBe Fingerprints.of(other)
    }

    @Test
    fun `an edited photo gets a new fingerprint and a deleted one disappears`() {
        val file = put("a.jpg", golden("pixel.jpg"))
        folders = setOf(root)
        source.items().size shouldBe 1

        Files.delete(file)
        names().shouldBeEmpty()
        put("a.jpg", golden("samsung.jpg"))
        source.items().single().descriptor.fingerprint shouldBe Fingerprints.of(golden("samsung.jpg"))
    }

    @Test
    fun `a file that is not a photo is not read again`() {
        put("fake.jpg", "text".toByteArray())
        folders = setOf(root)

        names().shouldBeEmpty()
        names().shouldBeEmpty()
    }

    @Test
    fun `the original bytes are opened, whole or in ranges`() {
        val bytes = golden("video.mp4")
        put("v.mp4", bytes)
        folders = setOf(root)
        val item = source.items().single()

        source.open(item).buffer().use { it.readByteArray() }.toList() shouldBe bytes.toList()
        source.openRanges(item).use { it.read(4, 8).toList() shouldBe bytes.copyOfRange(4, 12).toList() }
        source.openRanges(item).use {
            shouldThrow<IOException> { it.read(bytes.size.toLong() - 2, 10) }
        }
    }

    @Test
    fun `a file edited or deleted since it was listed is not sent`() {
        val file = put("a.jpg", golden("pixel.jpg"))
        folders = setOf(root)
        val item = source.items().single()

        Files.write(file, golden("pixel.jpg") + byteArrayOf(1))
        shouldThrow<IOException> { source.open(item) }

        Files.delete(file)
        shouldThrow<IOException> { source.open(item) }
        shouldThrow<IOException> { source.openRanges(item) }
    }

    @Test
    fun `nothing is held back or kept for confirmed and withdrawn files`() {
        val file = put("a.jpg", golden("pixel.jpg"))
        folders = setOf(root)
        val item = source.items().single()

        source.confirmed(item)
        source.withdrawn(item)

        Files.exists(file) shouldBe true // Files in the user's folders are never touched.
        source.items().size shouldBe 1
    }
}
