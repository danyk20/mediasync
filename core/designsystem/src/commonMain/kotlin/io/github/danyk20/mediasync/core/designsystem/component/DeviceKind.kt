/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.StringResource

/** `true` where the app runs on a computer (the desktop app sets it): screens then say "computer", not "phone". */
val LocalIsComputer = staticCompositionLocalOf { false }

/** [computer] on a computer, [phone] everywhere else: the words a screen shows for the device it runs on. */
@Composable
fun deviceText(phone: StringResource, computer: StringResource): StringResource =
    if (LocalIsComputer.current) computer else phone
