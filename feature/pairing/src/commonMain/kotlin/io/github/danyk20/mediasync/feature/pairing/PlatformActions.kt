/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.runtime.Composable

/** Hands a pairing link to the user: the system share sheet on Android, the clipboard on the desktop. */
@Composable
internal expect fun rememberLinkSharer(): (String) -> Unit

/** Reads the text on the clipboard (empty if there is none). */
@Composable
internal expect fun rememberClipboardReader(): suspend () -> String

/**
 * The live QR scanner, ready to show, or `null` where there is no camera to use: the desktop, or an Android phone
 * without the camera permission. While [active], a missing Android permission is requested. Pasting a link still works.
 */
@Composable
internal expect fun rememberQrScanner(active: Boolean, onCode: (String) -> Unit): (@Composable () -> Unit)?
