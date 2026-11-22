/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
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

// The desktop app (PLAN M8) opens files with the system's viewer.
@Composable
internal actual fun rememberMediaOpener(): (WaitingMedia) -> Boolean = { false }

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
