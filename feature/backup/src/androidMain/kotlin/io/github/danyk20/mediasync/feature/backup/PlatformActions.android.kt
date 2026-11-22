/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberBackupSystemActions(): BackupSystemActions {
    val context = LocalContext.current
    return remember(context) {
        BackupSystemActions(
            openApp = { context.openApp(it) },
            openStoreListing = { context.openStoreListing(it) },
            openStorageSettings = { context.openStorageSettings() },
        )
    }
}
