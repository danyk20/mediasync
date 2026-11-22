/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

@Composable
actual fun rememberDisplayLocale(): Locale = LocalConfiguration.current.locales[0]
