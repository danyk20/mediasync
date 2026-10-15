/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import java.io.InputStream

/**
 * The SHA-256 of an item's bytes as the receiving phone stored them, in lowercase hex: the content id its ledger
 * keeps (docs/PLAN.md §5.2). Backups are recorded under it.
 */
@JvmInline
value class ContentId(val hex: String) {
    init {
        require(hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Not a content id: $hex" }
    }

    override fun toString(): String = hex

    private companion object {
        const val HEX_LENGTH = 64
    }
}

/** Names one [ConnectorAccount] on this phone. */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty account id" }
    }

    override fun toString(): String = value
}

/**
 * One place the user backs up to: a Google account, a server, or another app on this phone.
 *
 * @property settings the values for the connector's [SettingsSchema], by field key.
 */
data class ConnectorAccount(
    val id: AccountId,
    val connectorId: ConnectorId,
    val displayName: String,
    val createdAtMillis: Long,
    val settings: Map<String, String> = emptyMap(),
)

/** Opens an item's bytes, from the first one, each time it's called. Close what it returns. */
fun interface ItemContent {
    fun open(): InputStream
}

/**
 * A received photo or video, ready to back up. A connector gets it after the shared preprocessing (docs/PLAN.md
 * §9.1), so it backs up exactly these bytes.
 *
 * @property location where the receiving phone keeps it, such as a MediaStore content URI.
 * @property capturedAtMillis the capture date the sending phone's gallery had, if any.
 */
data class BackupItem(
    val contentId: ContentId,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val location: String,
    val capturedAtMillis: Long? = null,
    val album: String? = null,
    val content: ItemContent,
) {
    val video: Boolean get() = mimeType.startsWith("video/")
}

/** Hears how far an upload got. */
fun interface ProgressSink {
    fun onProgress(bytesDone: Long, bytesTotal: Long)

    companion object {
        val None = ProgressSink { _, _ -> }
    }
}
