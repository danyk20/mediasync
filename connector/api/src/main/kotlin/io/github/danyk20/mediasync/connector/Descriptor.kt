/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import java.time.ZoneId
import java.time.ZoneOffset

/** Names a kind of connector, such as `google-photos-app`: lowercase letters, digits and dashes. */
@JvmInline
value class ConnectorId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Not a connector id: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[a-z][a-z0-9-]{1,39}")
    }
}

/** What a connector can do; the shared pipeline treats connectors differently by these (docs/PLAN.md §9.2). */
enum class Capability {
    /** Backs up videos as well as photos. */
    VIDEO,

    /** Can put items into albums, such as one per sending phone. */
    ALBUMS,

    /** Can confirm that an item it backed up is still there, which a cleanup needs (docs/PLAN.md §9.4). */
    VERIFY,

    /** Can find items by their content hash, without a receipt. */
    HASH_LOOKUP,

    /** Continues an interrupted upload where it stopped. */
    RESUMABLE_UPLOAD,

    /**
     * The service takes the date and place only from the file itself, so files without a date get one written in
     * before they're backed up (docs/PLAN.md §6.2). Google Photos works like this.
     */
    METADATA_FROM_FILE_ONLY,

    /**
     * Another app on this phone uploads, such as the Google Photos app: the connector only checks that files are
     * where that app backs them up, guides its setup, and leaves cleanup to it (docs/PLAN.md §9.3.1).
     */
    DELEGATED_TO_DEVICE_APP,
}

/**
 * What a service accepts, enforced before it's asked (docs/PLAN.md §9.3.2). `null` means no limit.
 *
 * @property requestsPerDay the service's daily allowance; each upload, check or lookup counts as one request.
 * @property quotaZone where the service's day starts: Google's quotas reset at midnight Pacific time.
 */
data class ConnectorLimits(
    val maxPhotoBytes: Long? = null,
    val maxVideoBytes: Long? = null,
    val batchSize: Int = 1,
    val requestsPerSecond: Double? = null,
    val requestsPerDay: Int? = null,
    val quotaZone: ZoneId = ZoneOffset.UTC,
    val parallelism: Int = 1,
) {
    init {
        require(maxPhotoBytes == null || maxPhotoBytes > 0) { "maxPhotoBytes: $maxPhotoBytes" }
        require(maxVideoBytes == null || maxVideoBytes > 0) { "maxVideoBytes: $maxVideoBytes" }
        require(batchSize >= 1) { "batchSize: $batchSize" }
        require(requestsPerSecond == null || requestsPerSecond > 0) { "requestsPerSecond: $requestsPerSecond" }
        require(requestsPerDay == null || requestsPerDay > 0) { "requestsPerDay: $requestsPerDay" }
        require(parallelism >= 1) { "parallelism: $parallelism" }
    }

    /** Whether the service takes an item of [size] bytes ([video] or photo). */
    fun allows(size: Long, video: Boolean): Boolean {
        val max = if (video) maxVideoBytes else maxPhotoBytes
        return max == null || size <= max
    }

    companion object {
        val None = ConnectorLimits()
    }
}

/** How a connector signs in to its service. */
enum class AuthKind { NONE, OAUTH2, API_KEY, BASIC }

/** Everything the app needs to know about a kind of connector before using it. */
data class ConnectorDescriptor(
    val id: ConnectorId,
    /** The service's name, such as "Google Photos": a brand, the same in every language. */
    val displayName: String,
    val capabilities: Set<Capability>,
    val limits: ConnectorLimits = ConnectorLimits.None,
    val authKind: AuthKind = AuthKind.NONE,
) {
    init {
        require(!delegated || (Capability.VERIFY !in capabilities && Capability.HASH_LOOKUP !in capabilities)) {
            "$id: another app does the backup, so MediaSync can't check it"
        }
        require(!delegated || authKind == AuthKind.NONE) { "$id: the other app signs in, not MediaSync" }
    }

    /** Another app on this phone does the backup ([Capability.DELEGATED_TO_DEVICE_APP]). */
    val delegated: Boolean get() = Capability.DELEGATED_TO_DEVICE_APP in capabilities

    operator fun contains(capability: Capability): Boolean = capability in capabilities
}
