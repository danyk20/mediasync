/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri

/** The photos and videos [Intent.ACTION_SEND] or [Intent.ACTION_SEND_MULTIPLE] carries, without duplicates. */
internal fun Intent.sharedUris(): List<Uri> {
    val streams = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))

        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

        else -> emptyList()
    }
    val clips = clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return (streams + clips).filter { it.scheme == "content" }.distinct()
}

/**
 * The gallery item behind a shared [uri], when it is one, as `content://media/...` without query parameters: the
 * gallery's own, or one another app wraps. Google Photos, for one, puts the gallery item's URI in its path. Items
 * only in a cloud, and other apps' files, have none: MediaSync copies those.
 */
internal fun galleryUriOf(uri: Uri): Uri? {
    if (uri.authority == MEDIA) return uri.buildUpon().clearQuery().fragment(null).build()
    val wrapped = uri.pathSegments.firstOrNull { it.startsWith("content://$MEDIA/") } ?: return null
    return galleryUriOf(wrapped.toUri())?.takeIf { it.authority == MEDIA }
}

private const val MEDIA = "media"
