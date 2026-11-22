/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import androidx.compose.runtime.Composable

/** The system's side of the backup screen: which pages it opens. Each returns `false` if the page isn't there. */
internal class BackupSystemActions(
    /** Opens the app [packageName]. */
    val openApp: (packageName: String) -> Boolean,
    /** The app's page in the store. */
    val openStoreListing: (packageName: String) -> Boolean,
    /** The phone's storage settings. */
    val openStorageSettings: () -> Boolean,
)

@Composable
internal expect fun rememberBackupSystemActions(): BackupSystemActions
