/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.database.MediaFingerprintDao
import io.github.danyk20.mediasync.core.database.MediaFingerprintEntity
import io.github.danyk20.mediasync.platform.desktop.FingerprintCache
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.decodeHex

/**
 * [FingerprintCache] in the database's `media_fingerprints` table, as the phone keeps it: the file's absolute path
 * where a phone has its MediaStore URI. Listings run off the UI thread, so blocking here is fine.
 */
class RoomFingerprintCache(private val dao: MediaFingerprintDao) : FingerprintCache {
    override fun get(path: String, size: Long, modifiedAtMillis: Long): ByteString? = runBlocking { dao.get(path) }
        ?.takeIf { it.mediaStoreSize == size && it.modifiedAtMillis == modifiedAtMillis && it.original }
        ?.fingerprint?.decodeHex()

    override fun put(path: String, size: Long, modifiedAtMillis: Long, fingerprint: ByteString) {
        runBlocking {
            dao.upsert(MediaFingerprintEntity(path, size, modifiedAtMillis, size, original = true, fingerprint.hex()))
        }
    }
}
