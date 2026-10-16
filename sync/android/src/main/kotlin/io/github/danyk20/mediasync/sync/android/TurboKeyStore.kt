/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.sync.engine.TURBO_KEY_BYTES
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * This phone's *Turbo receive* key, as a receiving phone (docs/PLAN.md §8.3): made once, told to paired sending
 * phones in every session, and kept out of backups, so a restored phone makes its own.
 */
@Singleton
class TurboKeyStore internal constructor(private val file: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    private var key: ByteString? = null

    @Synchronized
    fun current(): ByteString = key ?: (read() ?: create()).also { key = it }

    /**
     * Replaces the key after a sending phone was removed, so it can't find *Turbo receive* any more. The phones still
     * paired learn the new one in their next session.
     */
    @Synchronized
    fun renew() {
        key = create()
    }

    private fun read(): ByteString? =
        file.takeIf { it.length() == TURBO_KEY_BYTES.toLong() }?.readBytes()?.toByteString()

    private fun create(): ByteString {
        val bytes = ByteArray(TURBO_KEY_BYTES).also(SecureRandom()::nextBytes)
        file.parentFile?.mkdirs()
        val written = File(file.parentFile, "${file.name}.new").apply { writeBytes(bytes) }
        check(written.renameTo(file)) { "Couldn't store the Turbo key" }
        return bytes.toByteString()
    }

    private companion object {
        const val FILE_NAME = "turbo.key"
    }
}
