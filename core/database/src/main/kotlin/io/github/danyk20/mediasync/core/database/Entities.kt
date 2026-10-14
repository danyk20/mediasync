/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Hashes (device ids, fingerprints, content ids) are stored as lowercase hex: readable in a database
// inspector and compared as plain strings.

/** A device this one is paired with. */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    /** `PRODUCER` or `CONSUMER`. */
    val role: String,
    val pairedAtMillis: Long,
    /** Where the peer was last reached, e.g. `192.168.1.20:47420`; tried first next time. */
    val lastAddress: String? = null,
    val lastSeenMillis: Long? = null,
    /** Producer side: the consumer's sync schedule (`SyncSchedule.encode`), as it last said. Since version 2. */
    val schedule: String? = null,
    /** Producer side: the consumer's *Turbo receive* key (hex), as it last said. Since version 3. */
    val turboKey: String? = null,
)

/** Producer side: what a consumer has confirmed (docs/PLAN.md §5.6). */
@Entity(tableName = "sent_items", primaryKeys = ["consumerId", "fingerprint"])
data class SentItemEntity(
    val consumerId: String,
    val fingerprint: String,
    val contentId: String,
    val sentAtMillis: Long,
)

/** Consumer side: every item ever received, kept after a connector app deletes the file. */
@Entity(tableName = "received_items", indices = [Index("fingerprint"), Index("contentId")])
data class ReceivedItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val producerId: String,
    val fingerprint: String,
    val contentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val capturedAtMillis: Long?,
    val utcOffsetMinutes: Int?,
    val album: String?,
    val favorite: Boolean,
    /** Where it was stored, e.g. a MediaStore content URI. */
    val location: String,
    val receivedAtMillis: Long,
)

/** Consumer side: a file whose transfer was interrupted, kept so the next session resumes it. */
@Entity(tableName = "partial_transfers", primaryKeys = ["producerId", "fingerprint"])
data class PartialTransferEntity(
    val producerId: String,
    val fingerprint: String,
    val location: String,
    val startedAtMillis: Long,
)

/**
 * Producer side: fingerprints computed for MediaStore items, reused while the item's size and modification
 * time are unchanged, so a large library is only read once.
 */
@Entity(tableName = "media_fingerprints")
data class MediaFingerprintEntity(
    @PrimaryKey val mediaStoreUri: String,
    /** MediaStore's SIZE column when computed: with [modifiedAtMillis], tells whether the entry is stale. */
    val mediaStoreSize: Long,
    val modifiedAtMillis: Long,
    /** The file's actual length, which is what gets transferred. */
    val byteCount: Long,
    /** Whether the fingerprint covers the original bytes, with location (docs/PLAN.md §6.2). */
    val original: Boolean,
    val fingerprint: String,
)
