/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.database.LeftOutStore
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.SourceItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okio.ByteString

/**
 * The photos and videos the user chose not to sync, until they bring them back. They're known by fingerprint, so a copy
 * in another folder is left out too, while an edited photo, being another file, isn't.
 *
 * What the user shares to MediaSync is sent all the same: they asked for it then. Leaving out a shared item takes it
 * off the queue of shared items too.
 */
@Singleton
class LeftOutItems @Inject constructor(private val store: LeftOutStore, private val source: MediaSource) {
    /** Shared items left out since they were shared: a sync running now may still hold them as shared. */
    private val withdrawn: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The fingerprints left out, as they change. */
    val fingerprints: Flow<Set<ByteString>> = store.observe()

    /** Of [items], those left out, going by [fingerprints]: by default, those stored now, read on this thread. */
    fun of(items: List<SourceItem>, fingerprints: Set<ByteString> = store.fingerprints()): List<SourceItem> =
        items.filter { it.descriptor.fingerprint in fingerprints && (!it.shared || it.key in withdrawn) }

    suspend fun leaveOut(items: Collection<SourceItem>) = withContext(Dispatchers.IO) {
        store.add(items.map { it.descriptor.fingerprint })
        items.filter { it.shared }.forEach {
            withdrawn += it.key
            source.withdrawn(it)
        }
    }

    suspend fun bringBack(items: Collection<SourceItem>) = withContext(Dispatchers.IO) {
        store.remove(items.map { it.descriptor.fingerprint })
    }
}
