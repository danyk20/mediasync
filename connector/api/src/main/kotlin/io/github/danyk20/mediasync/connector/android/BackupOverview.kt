/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import io.github.danyk20.mediasync.connector.ConnectorAccount

/** How one account's backups stand, for the receiving phone's screens. */
data class BackupOverview(
    val account: ConnectorAccount,
    /** Handed to another app on this phone, and still here. */
    val handedOver: Int = 0,
    /** Backed up by another app, which then removed them from this phone (docs/PLAN.md §9.3.1). */
    val removedByApp: Int = 0,
    /** Uploaded by MediaSync. */
    val backedUp: Int = 0,
    /** Backed up or handed over since midnight. */
    val today: Int = 0,
    /** Received, and not backed up yet. */
    val waiting: Int = 0,
)
