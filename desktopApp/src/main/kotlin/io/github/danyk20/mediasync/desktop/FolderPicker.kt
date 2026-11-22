/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** Lets the user pick a folder; a seam so tests choose without a dialog. */
fun interface FolderPicker {
    /** The folder the user chose, starting from [current], or `null` if they cancelled. */
    suspend fun pick(current: Path?): Path?
}

/**
 * The system's folder dialog: AWT's native one on macOS (the only one that looks right there, switched to folders
 * by `apple.awt.fileDialogForDirectories`), Swing's `JFileChooser` elsewhere. Neither runs on the Compose thread.
 */
class AwtFolderPicker : FolderPicker {
    override suspend fun pick(current: Path?): Path? {
        val chosen = if (isMac()) {
            withContext(Dispatchers.IO) { pickWithFileDialog(current) }
        } else {
            withContext(Dispatchers.Swing) { pickWithChooser(current) }
        }
        return chosen?.takeIf { Files.isDirectory(it) }
    }

    private fun isMac() = System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }

    private fun pickWithFileDialog(current: Path?): Path? {
        System.setProperty(DIRECTORIES_PROPERTY, "true")
        try {
            val dialog = FileDialog(null as Frame?, TITLE, FileDialog.LOAD)
            current?.parent?.let { dialog.directory = it.toString() }
            dialog.isVisible = true
            val file = dialog.file ?: return null
            return Paths.get(dialog.directory.orEmpty(), file)
        } finally {
            System.clearProperty(DIRECTORIES_PROPERTY)
        }
    }

    private fun pickWithChooser(current: Path?): Path? {
        val chooser = JFileChooser(current?.toFile()).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = TITLE
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    private companion object {
        const val TITLE = "Choose the backup folder"
        const val DIRECTORIES_PROPERTY = "apple.awt.fileDialogForDirectories"
    }
}
