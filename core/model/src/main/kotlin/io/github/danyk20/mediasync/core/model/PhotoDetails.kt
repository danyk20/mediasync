/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/**
 * The *Photo details* privacy setting (docs/PLAN.md §6.3): which details inside photos and videos a sending phone
 * removes before they leave it. The picture itself, its orientation and colour profile always stay, and so does
 * the capture date, without which the photo would be filed under the day it arrived.
 */
enum class PhotoDetails {
    /** Everything is sent as it is. */
    KEEP_ALL,

    /** GPS in EXIF, location fields in XMP and IPTC, and video location are removed. */
    REMOVE_LOCATION,

    /**
     * Camera, device, location, edits and captions are removed, and so are album and favorite. Motion Photos and
     * Ultra HDR photos become ordinary photos, as those extras depend on the removed details.
     */
    REMOVE_ALL,
    ;

    companion object {
        val Default = KEEP_ALL
    }
}
