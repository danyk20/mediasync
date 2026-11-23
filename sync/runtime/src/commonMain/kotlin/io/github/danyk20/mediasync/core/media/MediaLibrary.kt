/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import io.github.danyk20.mediasync.core.model.SourceSelection
import kotlinx.coroutines.flow.Flow

/** A folder with photos or videos, as the sending phone's folder list shows it. */
data class MediaFolder(
    /** The MediaStore relative path, such as `DCIM/Camera/`. */
    val path: String,
    val itemCount: Int,
    val byteCount: Long,
    /** The newest item, for the folder's thumbnail. */
    val coverUri: String?,
) {
    val name: String get() = SourceSelection.nameOf(path)
}

/** This phone's photo and video library, by folder. */
interface MediaLibrary {
    /** Every folder with photos or videos, fullest first. */
    suspend fun folders(): List<MediaFolder>

    /** Emits whenever photos or videos are added, changed or removed. */
    fun changes(): Flow<Unit>
}
