/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.connector.android.BackupOverview
import kotlinx.coroutines.flow.Flow

/** What the backup screen needs from the phone: the apps that can back up, which are installed, and the accounts. */
interface BackupDevice {
    /** The connectors where another app on this phone does the backup, such as the Google Photos app. */
    val deviceApps: List<DeviceAppConnectorFactory>

    /** Whether the app [packageName] is installed and enabled. */
    fun isInstalled(packageName: String): Boolean

    /** Every account, with how its backups stand. */
    fun overviews(): Flow<List<BackupOverview>>

    /** Starts backing up through [factory], once the user has set that app up. */
    suspend fun connect(factory: BackupConnectorFactory)

    /** Stops backing up to [account]; its receipts stay, should it be set up again. */
    suspend fun disconnect(account: AccountId)
}
