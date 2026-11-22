/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.core.metadata.ByteReader
import io.github.danyk20.mediasync.core.metadata.CaptureDateGuess
import io.github.danyk20.mediasync.core.metadata.EmbeddedDates
import io.github.danyk20.mediasync.core.model.SendRange
import io.github.danyk20.mediasync.core.model.endMillis
import io.github.danyk20.mediasync.core.model.startMillis
import io.github.danyk20.mediasync.sync.engine.Fingerprints
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.RangeReader
import io.github.danyk20.mediasync.sync.engine.SourceItem
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import okio.ByteString
import okio.Source
import okio.source

/**
 * The photos and videos in the folders the user chose on this computer (docs/PLAN.md §2.2), each folder with
 * everything below it, taken within the days the user chose, if any. [folders] and [range] are read again before
 * every listing, so a new choice applies right away.
 *
 * - **What counts:** files with a photo or video extension whose first bytes agree ([MediaFiles.mimeTypeOf]). Hidden
 *   files and folders, photo library packages and symbolic links are left out. A file is listed once, however many
 *   chosen folders hold it.
 * - **Identity:** the [Fingerprints] of the content, as on a phone, so a file that is renamed or moved is not sent
 *   again, and the same photo from a phone and from this computer is one photo. [SourceItem.key] is the file's
 *   absolute path, used only to open it.
 * - **Dates:** the date inside the file is sent as the capture date; a file without one travels with its modification
 *   time and the receiving phone dates it from its name, else from that (docs/PLAN.md §6.2, point 3). The range
 *   applies the same way: the date inside, else the date in the name, else the modification time.
 * - **Files still being written** (changed within [settleMillis]) are left for the next listing. A modification time
 *   in the future (a wrong camera clock) doesn't count as recent.
 *
 * What was read about a file is kept until its size or modification time changes, so a listing of an unchanged library
 * reads no file contents. The fingerprints, which take the longest, are also kept in [cache], across restarts.
 */
class FolderMediaSource(
    private val folders: () -> Set<Path>,
    private val range: () -> SendRange = { SendRange.Everything },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val settleMillis: Long = SETTLE_MILLIS,
    private val cache: FingerprintCache = FingerprintCache.None,
) : MediaSource {

    /** What was read about one file: [fingerprint] only once the file was in range. */
    private class Known(
        val size: Long,
        val modifiedAtMillis: Long,
        val mimeType: String?,
        val meta: MediaMeta,
        /** The date the range is applied to. */
        val dateMillis: Long,
        var fingerprint: ByteString? = null,
    )

    private val known = ConcurrentHashMap<Path, Known>()

    override fun items(): List<SourceItem> {
        val days = range()
        val where = zone()
        val start = days.startMillis(where)
        val end = days.endMillis(where)
        val now = clock()
        val seen = LinkedHashMap<Path, FoundFile>()
        for (folder in folders().sortedBy { it.toString() }) {
            MediaFiles.find(folder.toAbsolutePath().normalize()).forEach { seen.putIfAbsent(it.path, it) }
        }
        val items = seen.values.mapNotNull { file ->
            if (file.size == 0L || (now - file.modifiedAtMillis) in 0 until settleMillis) return@mapNotNull null
            try {
                itemOf(file, where, start, end)
            } catch (_: IOException) {
                null // Gone or unreadable since it was found: not listed, not remembered.
            }
        }
        known.keys.retainAll(seen.keys) // Forget files that are gone.
        return items
    }

    private fun itemOf(file: FoundFile, zone: ZoneId, start: Long?, end: Long?): SourceItem? {
        val info = known[file.path]?.takeIf { it.size == file.size && it.modifiedAtMillis == file.modifiedAtMillis }
            ?: read(file, zone).also { known[file.path] = it }
        val mimeType = info.mimeType ?: return null
        if (info.dateMillis !in (start ?: Long.MIN_VALUE) until (end ?: Long.MAX_VALUE)) return null
        val fingerprint = info.fingerprint
            ?: cache.get(file.path.toString(), file.size, file.modifiedAtMillis)
            ?: channelOf(file.path).use { channel ->
                Fingerprints.of(file.size) { offset, count -> channel.readFully(offset, count) }
            }.also { cache.put(file.path.toString(), file.size, file.modifiedAtMillis, it) }
        info.fingerprint = fingerprint
        val descriptor = MediaDescriptor(
            fingerprint = fingerprint,
            size = file.size,
            mimeType = mimeType,
            displayName = file.path.fileName.toString(),
            meta = info.meta,
        )
        return SourceItem(file.path.toString(), descriptor)
    }

    /** Looks at the file's first bytes and its header for a date. */
    private fun read(file: FoundFile, zone: ZoneId): Known {
        val name = file.path.fileName.toString()
        channelOf(file.path).use { channel ->
            val head = channel.readFully(0, minOf(file.size, MediaFiles.HEAD_BYTES.toLong()).toInt())
            val mimeType = MediaFiles.mimeTypeOf(name, head)
                ?: return Known(file.size, file.modifiedAtMillis, null, MediaMeta(), file.modifiedAtMillis)
            val inside = EmbeddedDates.capturedAt(file.size, ByteReader(channel::readFully), zone)
            val meta = MediaMeta(
                capturedAtMillis = inside?.epochMillis,
                utcOffsetMinutes = inside?.utcOffsetMinutes?.takeIf { inside.offsetKnown },
                album = file.path.parent?.fileName?.toString(),
                modifiedAtMillis = file.modifiedAtMillis,
            )
            val date = CaptureDateGuess.resolve(inside?.epochMillis, name, file.modifiedAtMillis, zone)
            return Known(file.size, file.modifiedAtMillis, mimeType, meta, date?.epochMillis ?: file.modifiedAtMillis)
        }
    }

    override fun open(item: SourceItem): Source {
        val path = Paths.get(item.key)
        // A file edited since it was listed would fail its checksum on the other side: stop here instead.
        if (Files.size(path) != item.descriptor.size) throw IOException("The file changed since it was listed")
        return path.source()
    }

    override fun openRanges(item: SourceItem): RangeReader {
        val channel = channelOf(Paths.get(item.key))
        return object : RangeReader {
            override fun read(offset: Long, byteCount: Int): ByteArray = channel.readFully(offset, byteCount)

            override fun close() = channel.close()
        }
    }

    private fun channelOf(path: Path): FileChannel = FileChannel.open(path, StandardOpenOption.READ)

    private companion object {
        /** Long enough for a camera or a copy to finish writing a photo, short enough not to hold a sync back. */
        const val SETTLE_MILLIS = 5_000L
    }
}

/** Exactly [byteCount] bytes from [offset]. */
private fun FileChannel.readFully(offset: Long, byteCount: Int): ByteArray {
    val buffer = ByteBuffer.allocate(byteCount)
    while (buffer.hasRemaining()) {
        if (read(buffer, offset + buffer.position()) < 0) throw IOException("File shorter than expected")
    }
    return buffer.array()
}
