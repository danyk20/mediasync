/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** Opens the app [packageName]; `false` if it isn't on this phone. */
internal fun Context.openApp(packageName: String): Boolean =
    start(packageManager.getLaunchIntentForPackage(packageName))

/** The app's page in the Play Store, or on the web without the store. */
internal fun Context.openStoreListing(packageName: String): Boolean =
    start(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())) ||
        start(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))

/** The phone's storage settings, where a Pixel has *Smart Storage*; the settings if a phone has no such page. */
internal fun Context.openStorageSettings(): Boolean =
    start(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) || start(Intent(Settings.ACTION_SETTINGS))

private fun Context.start(intent: Intent?): Boolean = intent != null && try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
