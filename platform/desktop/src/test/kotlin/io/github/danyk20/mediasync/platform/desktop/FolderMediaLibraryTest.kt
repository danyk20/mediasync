/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The folders a computer offers to send, on temporary folders only. */
class FolderMediaLibraryTest {

    @TempDir
    lateinit var root: Path

    private fun golden(name: String): ByteArray =
        FolderMediaLibraryTest::class.java.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }

    private fun put(relative: String, bytes: ByteArray, modified: String = "2026-02-02T08:00:00Z"): Path =
        root.resolve(relative).also {
            Files.createDirectories(it.parent)
            Files.write(it, bytes)
            Files.setLastModifiedTime(it, FileTime.from(Instant.parse(modified)))
        }

    @Test
    fun `each candidate folder counts the photos and videos in it and below it`() = runBlocking<Unit> {
        val a = put("small/a.jpg", golden("pixel.jpg"))
        put("big/b.jpg", golden("samsung.jpg"), modified = "2026-03-03T08:00:00Z")
        val c = put("big/deep/c.mp4", golden("video.mp4"), modified = "2026-04-04T08:00:00Z")
        put("big/.hidden/d.jpg", golden("pixel.jpg"))
        put("big/notes.txt", "x".toByteArray())
        val library = FolderMediaLibrary { setOf(root.resolve("small"), root.resolve("big"), root.resolve("none")) }

        val folders = library.folders()

        folders.map { it.itemCount } shouldBe listOf(2, 1)
        folders[0].path shouldBe FolderMediaLibrary.folderPath(root.resolve("big"))
        folders[0].byteCount shouldBe golden("samsung.jpg").size + golden("video.mp4").size.toLong()
        folders[0].coverUri shouldBe c.toUri().toString()
        folders[0].name shouldBe "big"
        folders[1].path shouldBe FolderMediaLibrary.folderPath(root.resolve("small"))
        folders[1].coverUri shouldBe a.toUri().toString()
    }

    @Test
    fun `an empty or missing candidate has no cover, and no candidates no folders`() = runBlocking<Unit> {
        Files.createDirectories(root.resolve("empty"))

        FolderMediaLibrary { emptySet() }.folders().shouldBeEmpty()
        FolderMediaLibrary { setOf(root.resolve("empty")) }.folders().single().apply {
            itemCount shouldBe 0
            coverUri shouldBe null
        }
    }

    @Test
    fun `a folder's path is absolute with slashes and ends with one`() {
        val path = FolderMediaLibrary.folderPath(root.resolve("a/../b"))

        path shouldBe root.toAbsolutePath().normalize().resolve("b").toString().replace('\\', '/') + "/"
    }

    @Test
    fun `a new photo is noticed`() = runBlocking<Unit> {
        val folder = Files.createDirectories(root.resolve("watched"))
        val library = FolderMediaLibrary { setOf(folder) }

        val noticed = withTimeoutOrNull(WAIT_MILLIS) {
            coroutineScope {
                val change = async { library.changes().first() }
                delay(SETTLE_MILLIS) // Watching has begun.
                put("watched/a.jpg", golden("pixel.jpg"))
                change.await()
            }
        }

        noticed shouldBe Unit
    }

    @Test
    fun `a new folder is noticed`() = runBlocking<Unit> {
        val folder = Files.createDirectories(root.resolve("watched"))
        val library = FolderMediaLibrary { setOf(folder) }

        val noticed = withTimeoutOrNull(WAIT_MILLIS) {
            coroutineScope {
                val change = async { library.changes().first() }
                delay(SETTLE_MILLIS)
                Files.createDirectories(folder.resolve("new"))
                change.await()
            }
        }

        noticed shouldBe Unit
    }

    private companion object {
        /** macOS polls for changes about every ten seconds. */
        const val WAIT_MILLIS = 40_000L
        const val SETTLE_MILLIS = 500L
    }
}
