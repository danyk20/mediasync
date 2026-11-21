/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/** Ready-made choices of folders to send. Unlike a hand-picked list, they include matching folders added later. */
enum class SourcePreset {
    /** Folders under `DCIM/`, where camera apps save. */
    CAMERA,

    /** Camera folders and every `Screenshots` folder. */
    CAMERA_AND_SCREENSHOTS,

    /** Every folder with photos or videos. */
    EVERYTHING,
}

/**
 * Which of the sending phone's folders MediaSync sends (docs/PLAN.md §2.2).
 *
 * Folders are MediaStore relative paths such as `DCIM/Camera/` or `Pictures/Screenshots/`. MediaSync's own
 * receiving folder is left out of every preset: on a phone that used to receive, it holds other phones' photos.
 *
 * @property preset the preset in use, or `null` for exactly [folders].
 */
data class SourceSelection(val preset: SourcePreset?, val folders: Set<String> = emptySet()) {

    fun includes(folder: String): Boolean = when (preset) {
        SourcePreset.CAMERA -> isCameraFolder(folder)
        SourcePreset.CAMERA_AND_SCREENSHOTS -> isCameraFolder(folder) || isScreenshotsFolder(folder)
        SourcePreset.EVERYTHING -> !isMediaSyncFolder(folder)
        null -> folder in folders
    }

    companion object {
        val Default = SourceSelection(SourcePreset.CAMERA)

        fun of(folders: Set<String>) = SourceSelection(preset = null, folders = folders)

        /** Where camera apps save: `DCIM/` and its subfolders, except screenshots and MediaSync's own folder. */
        fun isCameraFolder(folder: String): Boolean =
            folder.startsWith(DCIM, ignoreCase = true) && !isScreenshotsFolder(folder) && !isMediaSyncFolder(folder)

        /** `Pictures/Screenshots/` on Pixels, `DCIM/Screenshots/` on Samsung phones, and the like. */
        fun isScreenshotsFolder(folder: String): Boolean = nameOf(folder).equals(SCREENSHOTS, ignoreCase = true)

        /** Where a receiving phone stores what it receives. */
        fun isMediaSyncFolder(folder: String): Boolean = folder.startsWith(MEDIA_SYNC, ignoreCase = true)

        /** The folder's own name: `Camera` for `DCIM/Camera/`. */
        fun nameOf(folder: String): String = folder.trimEnd('/').substringAfterLast('/')

        private const val DCIM = "DCIM/"
        private const val SCREENSHOTS = "Screenshots"
        private const val MEDIA_SYNC = "DCIM/MediaSync/"
    }
}
