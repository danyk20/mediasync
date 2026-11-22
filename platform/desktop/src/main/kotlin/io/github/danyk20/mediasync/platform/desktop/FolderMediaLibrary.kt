/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.core.media.MediaFolder
import io.github.danyk20.mediasync.core.media.MediaLibrary
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The folders on this computer the user can send: one entry per folder in [candidates], counting the photos and
 * videos in it and below it (by extension; the first bytes are checked when sending). A folder's path is absolute, with
 * `/` between names and at its end, as the selection stores it. The cover is the newest file's `file:` URI.
 *
 * [changes] watches the candidates and every folder below them. Where the system has no instant file notification
 * (macOS), changes are noticed within about ten seconds.
 */
class FolderMediaLibrary(private val candidates: () -> Set<Path>) : MediaLibrary {

    override suspend fun folders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        candidates().mapNotNull { folder ->
            val root = folder.toAbsolutePath().normalize()
            if (!Files.isDirectory(root)) return@mapNotNull null
            val files = MediaFiles.find(root)
            MediaFolder(
                path = folderPath(root),
                itemCount = files.size,
                byteCount = files.sumOf { it.size },
                coverUri = files.maxByOrNull { it.modifiedAtMillis }?.path?.toUri()?.toString(),
            )
        }.sortedWith(compareByDescending<MediaFolder> { it.itemCount }.thenBy { it.path })
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val watcher = FileSystems.getDefault().newWatchService()
        candidates().forEach { watchAll(watcher, it.toAbsolutePath().normalize()) }
        val job = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val key = watcher.take()
                    for (event in key.pollEvents()) {
                        val created = (key.watchable() as Path).resolve(event.context() as? Path ?: continue)
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(created)) {
                            watchAll(watcher, created) // A new folder, and what was put in it before it was watched.
                        }
                    }
                    trySend(Unit)
                    if (!key.reset()) key.cancel()
                }
            } catch (_: ClosedWatchServiceException) {
                // The collector went away.
            } catch (_: InterruptedException) {
                // As above.
            }
        }
        awaitClose {
            job.cancel()
            watcher.close()
        }
    }.conflate()

    private fun watchAll(watcher: WatchService, root: Path) {
        for (folder in MediaFiles.foldersUnder(root)) {
            try {
                folder.register(
                    watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
            } catch (_: IOException) {
                // Gone since it was listed, or the system's limit of watched folders: the next listing still sees it.
            }
        }
    }

    companion object {
        /** [folder] as the selection stores it: absolute, `/`-separated, ending in `/`. */
        fun folderPath(folder: Path): String =
            folder.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/') + "/"
    }
}
