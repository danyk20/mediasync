/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.icon

import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_battery
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_block
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_cellular
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_check_circle
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_close
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_download
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_expand_more
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_folder
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_info
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_more
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_notifications
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_play
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_radio_unchecked
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_sort
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_turbo
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_upload
import io.github.danyk20.mediasync.core.designsystem.generated.resources.ic_warning
import org.jetbrains.compose.resources.DrawableResource

/** Icons used across MediaSync (Material Symbols, Apache-2.0). */
object MediaSyncIcons {
    val Send: DrawableResource = Res.drawable.ic_upload

    val Receive: DrawableResource = Res.drawable.ic_download

    val Info: DrawableResource = Res.drawable.ic_info

    val Folder: DrawableResource = Res.drawable.ic_folder

    val Order: DrawableResource = Res.drawable.ic_sort

    val Warning: DrawableResource = Res.drawable.ic_warning

    val Notifications: DrawableResource = Res.drawable.ic_notifications

    val Battery: DrawableResource = Res.drawable.ic_battery

    val More: DrawableResource = Res.drawable.ic_more

    val Turbo: DrawableResource = Res.drawable.ic_turbo

    val MobileData: DrawableResource = Res.drawable.ic_cellular

    /** A section to open; turned upside down while it's open. */
    val Expand: DrawableResource = Res.drawable.ic_expand_more

    val Close: DrawableResource = Res.drawable.ic_close

    /** A chosen photo or video, while choosing. */
    val Selected: DrawableResource = Res.drawable.ic_check_circle

    /** One not chosen, while choosing. */
    val NotSelected: DrawableResource = Res.drawable.ic_radio_unchecked

    /** Marks a video among photos. */
    val Video: DrawableResource = Res.drawable.ic_play

    /** What the user chose not to sync. */
    val LeftOut: DrawableResource = Res.drawable.ic_block
}
