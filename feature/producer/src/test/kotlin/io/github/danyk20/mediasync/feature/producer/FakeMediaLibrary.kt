/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import io.github.danyk20.mediasync.core.media.MediaFolder
import io.github.danyk20.mediasync.core.media.MediaLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A photo library whose folders tests set, and whose changes they announce with [change]. */
class FakeMediaLibrary(var folders: List<MediaFolder> = emptyList()) : MediaLibrary {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun folders(): List<MediaFolder> = folders

    override fun changes(): Flow<Unit> = changes

    fun change(folders: List<MediaFolder> = this.folders) {
        this.folders = folders
        changes.tryEmit(Unit)
    }
}
