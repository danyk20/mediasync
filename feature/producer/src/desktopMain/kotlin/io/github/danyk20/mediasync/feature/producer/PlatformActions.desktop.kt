/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import java.awt.Desktop
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val NothingToAsk = HomeSystemActions({}, {}, {}, {}, {})

// A desktop has no permission prompts to answer; the desktop app (PLAN M8) opens its own settings.
@Composable
internal actual fun rememberHomeSystemActions(
    onPermissionsChanged: () -> Unit,
    onTurboAnswer: (granted: Boolean) -> Unit,
    onNotificationsDeclined: () -> Unit,
): HomeSystemActions = NothingToAsk

// Opens the file (its key is the absolute path, see FolderMediaSource) with the system's viewer.
@Composable
internal actual fun rememberMediaOpener(): (WaitingMedia) -> Boolean = { media -> openWithSystemViewer(media.key) }

@Suppress("TooGenericExceptionCaught") // Whatever stops the system from opening it: no viewer.
private fun openWithSystemViewer(path: String): Boolean = try {
    val file = File(path)
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop != null && file.isFile && desktop.isSupported(Desktop.Action.OPEN)) {
        desktop.open(file)
        true
    } else {
        false
    }
} catch (_: Exception) {
    false
}

@Composable
internal actual fun localizedDay(day: LocalDate, withYear: Boolean): String =
    day.format(DateTimeFormatter.ofPattern(if (withYear) "EEE, MMM d, yyyy" else "EEE, MMM d", Locale.getDefault()))

// Thumbnails come from the phone's gallery; the desktop app (PLAN M8) reads them from the files.
@Composable
internal actual fun rememberFolderCover(coverUri: String?, sizePx: Int): ImageBitmap? = null

@Composable
internal actual fun rememberPhotoThumbnail(uri: String, video: Boolean): ImageBitmap? = null

// A desktop has no back gesture.
@Composable
internal actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
