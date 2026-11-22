/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import java.util.Locale

/** The language the user's numbers, dates and weekday names are shown in. */
@Composable
expect fun rememberDisplayLocale(): Locale
