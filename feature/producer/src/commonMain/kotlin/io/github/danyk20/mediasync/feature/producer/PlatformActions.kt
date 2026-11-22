/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("MatchingDeclarationName") // The platform actions of several kinds, one file.

package io.github.danyk20.mediasync.feature.producer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import java.time.LocalDate

/** The system's side of the home screen: what it asks the user to allow, and which settings pages it opens. */
internal class HomeSystemActions(
    /** Asks for access to the photo library, with locations. */
    val requestLibraryAccess: () -> Unit,
    /** Asks to show sync progress as notifications, where the system asks for that. */
    val requestNotifications: () -> Unit,
    /** Asks for what Turbo transfer needs (nearby devices, or location). */
    val requestTurbo: () -> Unit,
    /** MediaSync's page in the system settings: permissions, battery use. */
    val openAppSettings: () -> Unit,
    /** The page where the location is turned on. */
    val openLocationSettings: () -> Unit,
)

/**
 * The system's side of the home screen. [onPermissionsChanged] after the user answered a prompt, [onTurboAnswer]
 * with the answer to Turbo's, and [onNotificationsDeclined] if the user declined to show notifications. On desktop
 * nothing needs asking: every action does nothing.
 */
@Composable
internal expect fun rememberHomeSystemActions(
    onPermissionsChanged: () -> Unit,
    onTurboAnswer: (granted: Boolean) -> Unit,
    onNotificationsDeclined: () -> Unit,
): HomeSystemActions

/** Opens a photo or video in the phone's gallery or player; `false` if no app can. */
@Composable
internal expect fun rememberMediaOpener(): (WaitingMedia) -> Boolean

/**
 * [day] as the gallery heading names it, in the user's language and locale: weekday, month and day, and [withYear]
 * the year.
 */
@Composable
internal expect fun localizedDay(day: LocalDate, withYear: Boolean): String

/** The newest photo or video of a folder, at [sizePx]; `null` while it loads, or if it can't be read. */
@Composable
internal expect fun rememberFolderCover(coverUri: String?, sizePx: Int): ImageBitmap?

/** The thumbnail of a photo or video; `null` while it loads, or if it can't be read. Those read before are kept. */
@Composable
internal expect fun rememberPhotoThumbnail(uri: String, video: Boolean): ImageBitmap?

/**
 * Where a source item's thumbnail is: its key without the query that asks for the original file (with its
 * location), which thumbnails don't need.
 */
internal fun thumbnailUri(key: String): String = key.substringBefore('?')

/** While [enabled], the system's back gesture calls [onBack] instead of leaving the screen. */
@Composable
internal expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
