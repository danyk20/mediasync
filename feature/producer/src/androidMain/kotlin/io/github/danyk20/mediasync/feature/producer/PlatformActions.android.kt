/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Size
import androidx.activity.compose.BackHandler as ActivityBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.danyk20.mediasync.core.media.MediaPermissions
import io.github.danyk20.mediasync.sync.android.TurboPermissions
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberHomeSystemActions(
    onPermissionsChanged: () -> Unit,
    onTurboAnswer: (granted: Boolean) -> Unit,
    onNotificationsDeclined: () -> Unit,
): HomeSystemActions {
    val context = LocalContext.current
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
    }
    val requestTurbo = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
        onTurboAnswer(it.isNotEmpty() && it.values.all { granted -> granted })
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) onNotificationsDeclined() // Declined: don't ask again.
        onPermissionsChanged()
    }
    return remember(context, requestPermissions, requestTurbo, requestNotifications) {
        HomeSystemActions(
            requestLibraryAccess = { requestPermissions.launch(MediaPermissions.required.toTypedArray()) },
            // Android asks for that from 13 on.
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            requestTurbo = { requestTurbo.launch(TurboPermissions.requested.toTypedArray()) },
            openAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            openLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
    }
}

@Composable
internal actual fun rememberMediaOpener(): (WaitingMedia) -> Boolean {
    val context = LocalContext.current
    return remember(context) { { media -> context.open(media) } }
}

/** Opens [media] in the phone's gallery or player; `false` if no app can. */
private fun Context.open(media: WaitingMedia): Boolean {
    val uri = media.uri.toUri()
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false // A shared file's copy: only this app reads it.
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, if (media.video) "video/*" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
internal actual fun localizedDay(day: LocalDate, withYear: Boolean): String {
    val locale = LocalConfiguration.current.locales[0]
    val skeleton = if (withYear) "EEEMMMdyyyy" else "EEEMMMd"
    return day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

@Composable
internal actual fun rememberFolderCover(coverUri: String?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(null, coverUri) {
        value = coverUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(uri.toUri(), Size(sizePx, sizePx), null).asImageBitmap()
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
        }
    }
    return cover
}

@Composable
internal actual fun rememberPhotoThumbnail(uri: String, video: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState(MediaThumbnails.cached(uri)?.asImageBitmap(), uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { MediaThumbnails.load(context, uri, video) }?.asImageBitmap()
        }
    }
    return image
}

@Composable
internal actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = ActivityBackHandler(enabled, onBack)
