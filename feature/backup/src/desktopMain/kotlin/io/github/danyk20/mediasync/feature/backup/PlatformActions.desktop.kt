/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import androidx.compose.runtime.Composable

private val NothingToOpen = BackupSystemActions({ false }, { false }, { false })

// A desktop has no Google Photos app to open; the backup is the receiving phone's (PLAN M8 decides what to show).
@Composable
internal actual fun rememberBackupSystemActions(): BackupSystemActions = NothingToOpen
