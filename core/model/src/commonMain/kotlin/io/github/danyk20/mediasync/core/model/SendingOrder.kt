/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/**
 * In which order a sending phone sends what's waiting (docs/PLAN.md §5). It decides what arrives first when a big
 * backlog is cut short; galleries sort by date anyway. Photos the user shared to MediaSync always go first.
 */
enum class SendingOrder {
    /** Chosen by the phone: newest first, but photos before videos over the internet, where more arrive sooner so. */
    AUTOMATIC,

    /** By when they were taken, the newest first. */
    NEWEST_FIRST,

    /** By when they were taken, the oldest first. */
    OLDEST_FIRST,

    /** Every photo, then every video; the newest first in each. */
    PHOTOS_FIRST,

    /** Every video, then every photo; the newest first in each. */
    VIDEOS_FIRST,
    ;

    /** The order to use on a connection: what [AUTOMATIC] means there, else this. */
    fun resolved(overInternet: Boolean): SendingOrder = when {
        this != AUTOMATIC -> this
        overInternet -> PHOTOS_FIRST
        else -> NEWEST_FIRST
    }

    companion object {
        val Default = AUTOMATIC
    }
}
