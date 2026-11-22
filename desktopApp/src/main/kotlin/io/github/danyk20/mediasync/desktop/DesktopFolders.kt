/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.platform.desktop.FolderMediaLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * The folders a computer sends. What is chosen is the settings' [SourceSelection] holding folders (absolute paths,
 * as [FolderMediaLibrary.folderPath] writes them); a preset (a phone's idea) chooses nothing here.
 *
 * The folder list offers the folders the user added ([listed], chosen or not: an unchecked folder stays on the list, as
 * on Android), the chosen folders and the [defaults] that exist, such as Pictures. A folder leaves the list only when
 * it is gone from the disk.
 */
class DesktopFolders(
    private val settings: SettingsRepository,
    private val store: DataStore<Preferences>,
    private val defaults: List<Path>,
) {

    /** The chosen folders that still exist, read again at every call. */
    fun chosen(): Set<Path> = runBlocking { settings.sourceSelection.first() }.chosenFolders()
        .map { Paths.get(it) }
        .filterTo(LinkedHashSet()) { Files.isDirectory(it) }

    /** What the folder list shows: the added and chosen folders and the default ones, those that exist. */
    fun candidates(): Set<Path> {
        val listed = runBlocking { store.data.first()[ListedKey].orEmpty() }.map { Paths.get(it) }
        return (listed + chosen() + defaults).filterTo(LinkedHashSet()) { Files.isDirectory(it) }
    }

    /** Emits when the choice or the list changes, so the list shows an added folder at once. */
    val changes: Flow<Unit> = combine(settings.sourceSelection, store.data) { _, _ -> }

    /** Adds [folder] to the list and to the chosen folders. */
    suspend fun add(folder: Path) {
        val path = FolderMediaLibrary.folderPath(folder)
        store.edit { preferences -> preferences[ListedKey] = preferences[ListedKey].orEmpty() + path }
        val chosen = settings.sourceSelection.first().chosenFolders()
        settings.setSourceSelection(SourceSelection.of(chosen + path))
    }

    private fun SourceSelection.chosenFolders(): Set<String> = if (preset == null) folders else emptySet()

    companion object {
        /** The folders the user added, as the selection stores them. */
        private val ListedKey = stringSetPreferencesKey("listed_folders")

        /** Pictures, and Movies (macOS) or Videos: where people keep photos. [home] is the user's home folder. */
        fun defaultsIn(home: Path): List<Path> =
            listOf(home.resolve("Pictures"), home.resolve("Movies"), home.resolve("Videos"))
    }
}
