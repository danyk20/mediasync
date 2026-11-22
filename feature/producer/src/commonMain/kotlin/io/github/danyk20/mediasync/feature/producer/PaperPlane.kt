/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.danyk20.mediasync.sync.engine.SendingItem

/**
 * The sending animation (docs/PLAN.md §3.3): [item]'s thumbnail, on a sheet of paper, folds into a paper airplane
 * that flies off as fast as the file goes over the link; then the next file's sheet comes. With the phone's
 * animations off, the sheet stays still, with a ring going round instead.
 *
 * [bytesPerSecond]: the transfer's speed, for the first moments of a file. Drawn with the platform's own canvas: on
 * desktop it isn't drawn yet.
 */
@Composable
internal expect fun PaperPlane(item: SendingItem?, bytesPerSecond: Long?, modifier: Modifier)
