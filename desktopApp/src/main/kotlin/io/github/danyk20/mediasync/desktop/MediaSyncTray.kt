/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import java.awt.RenderingHints
import java.awt.image.BufferedImage

internal const val TRAY_TOOLTIP = "MediaSync"
internal const val TRAY_WAITING_TOOLTIP = "MediaSync: waiting for the keychain..."

/** The tray icon with "Show MediaSync" and "Quit"; only call it where a tray exists. Excluded from coverage (UI). */
@Composable
internal fun ApplicationScope.MediaSyncTray(tooltip: String, onShow: () -> Unit, onQuit: () -> Unit) {
    Tray(icon = TrayIcon, tooltip = tooltip, onAction = onShow) {
        Item("Show MediaSync", onClick = onShow)
        Item("Quit", onClick = onQuit)
    }
}

/** Whether this system has a tray at all (some Linux desktops don't). */
internal fun trayAvailable(): Boolean = isTraySupported

/** A generated icon, no image file: a blue rounded square with a white circle. */
private val TrayIcon: Painter = BitmapPainter(drawIcon())

private fun drawIcon(): ImageBitmap {
    val size = ICON_SIZE
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = java.awt.Color(BLUE)
    g.fillRoundRect(0, 0, size, size, size / QUARTER, size / QUARTER)
    g.color = java.awt.Color.WHITE
    g.fillOval(size / QUARTER, size / QUARTER, size / HALF, size / HALF)
    g.dispose()
    return image.toComposeImageBitmap()
}

private const val ICON_SIZE = 64
private const val QUARTER = 4
private const val HALF = 2
private const val BLUE = 0x1E6FD9
