/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.connector.android.BackupOverview
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.connector.android.InstalledApps
import kotlinx.coroutines.flow.Flow

/** [BackupDevice] on Android: [CloudBackup] and the package manager. */
@ContributesBinding(AppScope::class)
@Inject
class AndroidBackupDevice(private val backup: CloudBackup, private val installedApps: InstalledApps) : BackupDevice {
    override val deviceApps: List<DeviceAppConnectorFactory> get() = backup.deviceApps

    override fun isInstalled(packageName: String): Boolean = installedApps.isInstalled(packageName)

    override fun overviews(): Flow<List<BackupOverview>> = backup.overviews()

    override suspend fun connect(factory: BackupConnectorFactory) = backup.connect(factory)

    override suspend fun disconnect(account: AccountId) = backup.disconnect(account)
}
