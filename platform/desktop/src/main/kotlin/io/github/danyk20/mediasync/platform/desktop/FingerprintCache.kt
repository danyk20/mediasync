/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import okio.ByteString

/**
 * Remembers the fingerprint of a file for as long as its size and modification time stay the same, so a restart of
 * the app does not read every file again. [FolderMediaSource] asks before it reads and tells after; the desktop app
 * backs it with the database.
 */
interface FingerprintCache {
    /** The remembered fingerprint of [path] if it had this [size] and [modifiedAtMillis]; else `null`. */
    fun get(path: String, size: Long, modifiedAtMillis: Long): ByteString?

    /** Remembers [fingerprint] for [path] as it is now. */
    fun put(path: String, size: Long, modifiedAtMillis: Long, fingerprint: ByteString)

    /** Remembers nothing: the default. */
    object None : FingerprintCache {
        override fun get(path: String, size: Long, modifiedAtMillis: Long): ByteString? = null

        override fun put(path: String, size: Long, modifiedAtMillis: Long, fingerprint: ByteString) = Unit
    }
}
