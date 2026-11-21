/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlinx.coroutines.launch

@Composable
internal actual fun rememberLinkSharer(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { link -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(link))) } }
}

@Composable
internal actual fun rememberClipboardReader(): suspend () -> String {
    val clipboard = LocalClipboard.current
    return {
        val content = clipboard.getClipEntry()?.nativeClipEntry as? Transferable
        if (content?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) {
            content.getTransferData(DataFlavor.stringFlavor) as? String ?: ""
        } else {
            ""
        }
    }
}

// No camera on the desktop: it pairs by pasting the link (the full desktop flow is PLAN M9).
@Composable
internal actual fun rememberQrScanner(active: Boolean, onCode: (String) -> Unit): (@Composable () -> Unit)? = null
