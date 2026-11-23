/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.protocol.OfferItem

internal fun MediaDescriptor.toOfferItem(offerId: Int) = OfferItem(
    offerId = offerId,
    size = size,
    fingerprint = fingerprint,
    mimeType = mimeType,
    displayName = displayName,
    capturedAtMillis = meta.capturedAtMillis,
    utcOffsetMinutes = meta.utcOffsetMinutes,
    album = meta.album,
    favorite = meta.favorite,
    modifiedAtMillis = meta.modifiedAtMillis,
)

internal fun OfferItem.toDescriptor() = MediaDescriptor(
    fingerprint = fingerprint,
    size = size,
    mimeType = mimeType,
    displayName = displayName,
    meta = MediaMeta(capturedAtMillis, utcOffsetMinutes, album, favorite, modifiedAtMillis),
)
