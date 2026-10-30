/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.icon

import androidx.annotation.DrawableRes
import io.github.danyk20.mediasync.core.designsystem.R

/** Icons used across MediaSync (Material Symbols, Apache-2.0). */
object MediaSyncIcons {
    @DrawableRes val Send: Int = R.drawable.ic_upload

    @DrawableRes val Receive: Int = R.drawable.ic_download

    @DrawableRes val Info: Int = R.drawable.ic_info

    @DrawableRes val Folder: Int = R.drawable.ic_folder

    @DrawableRes val Order: Int = R.drawable.ic_sort

    @DrawableRes val Warning: Int = R.drawable.ic_warning

    @DrawableRes val Notifications: Int = R.drawable.ic_notifications

    @DrawableRes val Battery: Int = R.drawable.ic_battery

    @DrawableRes val More: Int = R.drawable.ic_more

    @DrawableRes val Turbo: Int = R.drawable.ic_turbo

    @DrawableRes val MobileData: Int = R.drawable.ic_cellular

    /** A section to open; turned upside down while it's open. */
    @DrawableRes val Expand: Int = R.drawable.ic_expand_more

    @DrawableRes val Close: Int = R.drawable.ic_close

    /** A chosen photo or video, while choosing. */
    @DrawableRes val Selected: Int = R.drawable.ic_check_circle

    /** One not chosen, while choosing. */
    @DrawableRes val NotSelected: Int = R.drawable.ic_radio_unchecked

    /** Marks a video among photos. */
    @DrawableRes val Video: Int = R.drawable.ic_play

    /** What the user chose not to sync. */
    @DrawableRes val LeftOut: Int = R.drawable.ic_block
}
