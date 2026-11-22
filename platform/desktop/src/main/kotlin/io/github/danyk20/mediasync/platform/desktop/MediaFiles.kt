/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A file that may be a photo or a video, found by its name. */
internal class FoundFile(val path: Path, val size: Long, val modifiedAtMillis: Long)

/** Finding photos and videos in a computer's folders, and telling by a file's first bytes what it really is. */
internal object MediaFiles {

    /**
     * The files with a photo or video extension in [root] and everything below it. Hidden files and folders (a name
     * starting with a dot), photo library packages and symbolic links are left out: a link may lead out of the folder
     * or around in a circle. Unreadable places are skipped.
     */
    fun find(root: Path): List<FoundFile> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = mutableListOf<FoundFile>()
        val visitor: FileVisitor<Path> = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && isSkippedFolder(dir.fileName.toString())) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (attrs.isRegularFile && !name.startsWith(".") && extensionOf(name) in EXTENSIONS) {
                    found.add(FoundFile(file, attrs.size(), attrs.lastModifiedTime().toMillis()))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        try {
            Files.walkFileTree(root, visitor)
        } catch (_: IOException) {
            // The folder went away during the walk: what was found stands.
        }
        return found
    }

    /** [path]'s folder and every folder below it, hidden and package ones left out, for watching. */
    fun foldersUnder(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val folders = mutableListOf<Path>()
        val visitor: FileVisitor<Path> = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && isSkippedFolder(dir.fileName.toString())) return FileVisitResult.SKIP_SUBTREE
                folders.add(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        try {
            Files.walkFileTree(root, visitor)
        } catch (_: IOException) {
            // As in find.
        }
        return folders
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * The MIME type of the file called [name] whose first bytes are [head], or `null` if it isn't a photo or video
     * MediaSync can send: its extension names a kind and its first bytes must agree. RAW and MPEG files have too many
     * signatures to list; their extension is trusted.
     */
    fun mimeTypeOf(name: String, head: ByteArray): String? {
        val extension = extensionOf(name)
        val mime = EXTENSIONS[extension] ?: return null
        val ok = when (extension) {
            "jpg", "jpeg" -> head.startsWith(JPEG)

            "png" -> head.startsWith(PNG)

            "gif" -> head.startsWith("GIF8".toByteArray())

            "bmp" -> head.startsWith("BM".toByteArray())

            "webp" -> head.startsWith("RIFF".toByteArray()) && head.startsWith("WEBP".toByteArray(), at = 8)

            "heic", "heif", "avif", "mp4", "m4v", "mov", "3gp", "3g2" ->
                ISOBMFF_FIRST_BOXES.any { head.startsWith(it.toByteArray(), at = 4) }

            "mkv", "webm" -> head.startsWith(EBML)

            "avi" -> head.startsWith("RIFF".toByteArray()) && head.startsWith("AVI ".toByteArray(), at = 8)

            else -> head.isNotEmpty() // RAW, TIFF, MPEG, MTS: the extension decides.
        }
        return mime.takeIf { ok }
    }

    private fun isSkippedFolder(name: String) = name.startsWith(".") || PACKAGE_SUFFIXES.any { name.endsWith(it, true) }

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
        size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

    const val HEAD_BYTES = 16

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val ISOBMFF_FIRST_BOXES = listOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot")
    private val PACKAGE_SUFFIXES = listOf(".photoslibrary", ".photolibrary", ".aplibrary")

    /** By lower-case extension; the types the receiving side's *Photo details* handling knows by name. */
    private val EXTENSIONS: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "dng" to "image/x-adobe-dng",
        "cr2" to "image/x-canon-cr2",
        "nef" to "image/x-nikon-nef",
        "arw" to "image/x-sony-arw",
        "rw2" to "image/x-panasonic-rw2",
        "orf" to "image/x-olympus-orf",
        "raf" to "image/x-fuji-raf",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "mts" to "video/mp2t",
        "m2ts" to "video/mp2t",
    )
}
