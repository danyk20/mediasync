/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What *Reset MediaSync* does on a computer: the desktop app provides it (it forgets the role and the paired devices,
 * then closes); `null` where nothing provides it, and the button does nothing.
 */
val LocalResetMediaSync = staticCompositionLocalOf<(() -> Unit)?> { null }

// The desktop app (PLAN M8) shares and saves the log its own way.
@Composable
internal actual fun rememberLogSharer(): suspend (text: String) -> Unit = {}

@Composable
internal actual fun rememberLogSaver(text: suspend () -> String): () -> Unit = {}

@Composable
internal actual fun rememberResetMediaSync(): () -> Unit = LocalResetMediaSync.current ?: {}
