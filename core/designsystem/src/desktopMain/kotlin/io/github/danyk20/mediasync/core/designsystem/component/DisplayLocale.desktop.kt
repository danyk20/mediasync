/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import java.util.Locale

@Composable
actual fun rememberDisplayLocale(): Locale = Locale.getDefault()
