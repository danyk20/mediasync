/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.danyk20.mediasync.sync.engine.SendingItem

// The paper airplane is drawn with Android's canvas; the desktop app (PLAN M8) shows the plain progress card.
@Composable
internal actual fun PaperPlane(item: SendingItem?, bytesPerSecond: Long?, modifier: Modifier) = Unit
