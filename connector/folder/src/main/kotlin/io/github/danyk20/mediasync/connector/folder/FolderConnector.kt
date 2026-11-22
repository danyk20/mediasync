/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.folder

import io.github.danyk20.mediasync.connector.AuthState
import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.ConnectorDescriptor
import io.github.danyk20.mediasync.connector.ConnectorError
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.UploadResult
import io.github.danyk20.mediasync.connector.VerifyResult
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Backs up into a folder on this computer: a local disk, a NAS share that is mounted, or a folder another app syncs.
 * It stores each item's bytes as they are (so its date and place survive) and gives the file the item's capture
 * date as its modification time.
 *
 * [root] is never created: if it isn't there (an unplugged disk, an unmounted share) nothing is written, rather than
 * writing into an empty mount point on the system disk. Folders below it are created as needed.
 */
internal class FolderConnector(
    private val root: Path?,
    private val layout: FolderLayoutKind,
    private val zone: ZoneId,
) : BackupConnector {

    override val descriptor: ConnectorDescriptor get() = FolderDescriptor

    override suspend fun authState(): AuthState = AuthState.NotNeeded

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult = withContext(Dispatchers.IO) {
        val folder = availableRoot() ?: return@withContext unavailable()
        try {
            store(folder, item, progress)
        } catch (_: IOException) {
            // The message names no file or place: it reaches the diagnostic log.
            UploadResult.Failed(ConnectorError.Transient("The backup folder could not be written"))
        }
    }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> =
        withContext(Dispatchers.IO) {
            val folder = availableRoot()
            receipts.associate { it.contentId to check(folder, it.remoteId) }
        }

    private fun availableRoot(): Path? = root?.takeIf { Files.isDirectory(it) }

    private fun unavailable() = UploadResult.Failed(ConnectorError.Transient("The backup folder is not available"))

    /** `UNKNOWN` while the folder itself can't be reached: a copy isn't missing just because the share is offline. */
    private fun check(folder: Path?, remoteId: String): VerifyResult {
        if (folder == null) return VerifyResult.UNKNOWN
        val file = resolveRemoteId(folder, remoteId) ?: return VerifyResult.MISSING
        return try {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) VerifyResult.PRESENT else VerifyResult.MISSING
        } catch (_: SecurityException) {
            VerifyResult.UNKNOWN
        }
    }

    private suspend fun store(folder: Path, item: BackupItem, progress: ProgressSink): UploadResult {
        val directory = layout.directoryFor(folder, item.capturedAtMillis, zone)
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX)
        try {
            val digest = copyTo(temp, item, progress)
            item.capturedAtMillis?.let { setCaptureTimes(temp, FileTime.fromMillis(it)) }
            return place(folder, directory, temp, safeFileName(item.displayName), digest)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Sets the modification time to the capture date and, where the file system allows it, the creation time too.
     * Some systems and file systems ignore or refuse a creation time: that must never fail the copy.
     */
    private fun setCaptureTimes(file: Path, captured: FileTime) {
        Files.setLastModifiedTime(file, captured)
        try {
            Files.getFileAttributeView(file, BasicFileAttributeView::class.java)?.setTimes(captured, null, captured)
        } catch (_: IOException) {
            // The creation time is best effort; the modification time above is what matters.
        } catch (_: UnsupportedOperationException) {
            // This file system has no creation time to set.
        }
    }

    /** Writes the item to [temp], reporting progress; returns the SHA-256 of what was written. */
    private suspend fun copyTo(temp: Path, item: BackupItem, progress: ProgressSink): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        item.content.open().use { input ->
            FileOutputStream(temp.toFile()).use { output ->
                pump(input, output, digest, item, progress)
                output.flush()
                try {
                    output.fd.sync()
                } catch (_: SyncFailedException) {
                    // Some network shares can't flush; the file is closed and complete all the same.
                }
            }
        }
        return digest.digest()
    }

    private suspend fun pump(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        item: BackupItem,
        progress: ProgressSink,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            done += read
            progress.onProgress(done.coerceAtMost(item.size), item.size)
        }
    }

    /**
     * Moves [temp] to the first free name (`IMG.jpg`, `IMG (1).jpg`, ...), or finds that a file with the same bytes is
     * already there: uploading the same item twice keeps one copy.
     */
    private fun place(folder: Path, directory: Path, temp: Path, name: String, digest: ByteArray): UploadResult {
        val size = Files.size(temp)
        for (attempt in 0 until MAX_NAME_ATTEMPTS) {
            val candidate = directory.resolve(withSuffix(name, attempt))
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (sameBytes(candidate, size, digest)) return UploadResult.Uploaded(remoteIdOf(folder, candidate), 0)
                continue
            }
            try {
                Files.move(temp, candidate) // Never replaces a file.
                return UploadResult.Uploaded(remoteIdOf(folder, candidate), size)
            } catch (_: FileAlreadyExistsException) {
                // Taken in the meantime: try the next name.
            }
        }
        return UploadResult.Failed(ConnectorError.Transient("Too many files with the same name"))
    }

    private fun sameBytes(file: Path, size: Long, digest: ByteArray): Boolean =
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == size &&
            MessageDigest.isEqual(sha256(file), digest)

    private fun sha256(file: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        Files.newInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_NAME_ATTEMPTS = 1_000
    }
}

/** Where [FolderConnector] keeps what it is still writing; never left behind. */
internal const val TEMP_PREFIX = ".mediasync-"
internal const val TEMP_SUFFIX = ".part"

/** The receipt's remote id: the file's path below the folder, always with `/`, so it means the same on every system. */
internal fun remoteIdOf(root: Path, file: Path): String = root.relativize(file).joinToString("/") { it.toString() }

/** The file a remote id names, or `null` if it would lie outside [root]. */
internal fun resolveRemoteId(root: Path, remoteId: String): Path? {
    val resolved = remoteId.split('/').fold(root) { path, part -> path.resolve(part) }.normalize()
    return resolved.takeIf { it.startsWith(root.normalize()) && it != root.normalize() }
}

/** `IMG.jpg` for attempt 0, then `IMG (1).jpg`, `IMG (2).jpg`, ... */
internal fun withSuffix(name: String, attempt: Int): String {
    if (attempt == 0) return name
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($attempt)${name.substring(dot)}" else "$name ($attempt)"
}
