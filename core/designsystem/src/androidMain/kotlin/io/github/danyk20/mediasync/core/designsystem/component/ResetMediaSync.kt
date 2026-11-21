/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import android.app.ActivityManager
import android.content.Context

/**
 * Back to how MediaSync was installed: its role, paired phones, settings and history are gone, and the app closes.
 * Photos and videos stay where they are; they aren't the app's data.
 */
fun Context.resetMediaSync() {
    getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
}
