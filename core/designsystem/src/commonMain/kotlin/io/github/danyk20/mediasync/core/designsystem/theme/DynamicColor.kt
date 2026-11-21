/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** The wallpaper-based palette (Android 12+), or null where the platform has none. */
@Composable
internal expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?
