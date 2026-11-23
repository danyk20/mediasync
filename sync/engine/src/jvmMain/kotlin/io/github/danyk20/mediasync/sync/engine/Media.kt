/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import okio.ByteString

/**
 * Gallery details that live outside the file, in the producer's MediaStore (docs/PLAN.md §6.1).
 * Everything inside the file (EXIF, XMP, video metadata) travels byte for byte and isn't modelled here.
 */
data class MediaMeta(
    val capturedAtMillis: Long? = null,
    val utcOffsetMinutes: Int? = null,
    val album: String? = null,
    val favorite: Boolean = false,
    val modifiedAtMillis: Long? = null,
)

/** What both sides know about one photo or video before its bytes move. */
data class MediaDescriptor(
    /** See [Fingerprints]. Identifies the content cheaply; the same file on two phones has the same one. */
    val fingerprint: ByteString,
    val size: Long,
    val mimeType: String,
    val displayName: String,
    val meta: MediaMeta = MediaMeta(),
)

/**
 * A producer-side item: [key] is the source's own handle, e.g. a MediaStore id. [shared]: the user shared it to
 * MediaSync from a gallery app, to send now, so it goes before everything else.
 */
data class SourceItem(val key: String, val descriptor: MediaDescriptor, val shared: Boolean = false)

/** A consumer-side record of a stored item; kept even after a connector app deletes the file. */
data class ReceivedItem(
    val producer: DeviceId,
    val descriptor: MediaDescriptor,
    /** SHA-256 of the complete stored bytes. */
    val contentId: ByteString,
    /** Where it was stored, e.g. a content URI. */
    val location: String,
)
