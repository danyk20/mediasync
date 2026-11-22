/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.danyk20.mediasync.sync.android.TurboPermissions
import java.util.Locale

@Composable
internal actual fun rememberConsumerSystemActions(
    onNotificationsAnswer: (granted: Boolean) -> Unit,
    onTurboAnswer: () -> Unit,
    onTurboReceiveAnswer: (allGranted: Boolean) -> Unit,
): ConsumerSystemActions {
    val context = LocalContext.current
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onNotificationsAnswer(it)
    }
    val requestTurbo = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onTurboAnswer()
    }
    val requestTurboReceive = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onTurboReceiveAnswer(it.isNotEmpty() && it.values.all { granted -> granted })
    }
    return remember(context, requestNotifications, requestTurbo, requestTurboReceive) {
        ConsumerSystemActions(
            requestNotifications = {
                // Android asks for that from 13 on.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            requestTurbo = { requestTurbo.launch(TurboPermissions.requested.toTypedArray()) },
            requestTurboReceive = { requestTurboReceive.launch(TurboPermissions.requested.toTypedArray()) },
            openAppSettings = {
                context.startActivity(settingsOfThisApp(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            },
            openStorageSettings = { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) },
            openLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            openDisplayOverOtherApps = {
                context.startActivity(settingsOfThisApp(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            },
        )
    }
}

/** The system settings page [action] for MediaSync. */
private fun settingsOfThisApp(context: Context, action: String) =
    Intent(action, Uri.fromParts("package", context.packageName, null))

@Composable
internal actual fun rememberSignInBrowser(onUnavailable: () -> Unit): (url: String) -> Unit {
    val context = LocalContext.current
    val unavailable by rememberUpdatedState(onUnavailable)
    return remember(context) {
        { url ->
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
            } catch (_: ActivityNotFoundException) {
                unavailable()
            }
        }
    }
}

@Composable
internal actual fun rememberIs24HourFormat(): Boolean =
    android.text.format.DateFormat.is24HourFormat(LocalContext.current)

@Composable
internal actual fun rememberDisplayLocale(): Locale = LocalConfiguration.current.locales[0]
