/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice

/** The runtime permissions a producer needs to read its photo library with locations. */
@ExercisedOnDevice(coveredBy = "core:media MediaStoreTest")
object MediaPermissions {

    /** Everything to request; Android shows them as one "photos and videos" prompt plus one for locations. */
    val required: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    /**
     * Whether the whole library is readable. On Android 14+ a user may grant access to selected photos only,
     * which isn't enough for automatic sync.
     */
    fun canReadLibrary(context: Context): Boolean = required
        .filter { it != Manifest.permission.ACCESS_MEDIA_LOCATION }
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Whether locations stay in the photos that are sent (docs/PLAN.md §6.2). */
    fun canReadLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
}
