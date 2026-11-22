/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.core.media.ReceivedNames
import io.github.danyk20.mediasync.sync.android.BackgroundSetup
import io.github.danyk20.mediasync.sync.android.SetupResult
import io.github.danyk20.mediasync.sync.android.SetupStep
import io.github.danyk20.mediasync.sync.android.TurboKeyStore
import io.github.danyk20.mediasync.sync.android.WakeUpCheck
import io.github.danyk20.mediasync.sync.android.WakeUpStatus
import io.github.danyk20.mediasync.sync.android.WorkerSetup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [ConsumerDevice] on Android: permissions, the Turbo key, received files' names, wake-up checks and cloud backup. */
@ContributesBinding(AppScope::class)
@Inject
class AndroidConsumerDevice(
    private val setup: BackgroundSetup,
    private val turboKeys: TurboKeyStore,
    private val backup: CloudBackup,
    private val names: ReceivedNames,
    wakeUps: WakeUpCheck,
) : ConsumerDevice {
    override fun canNotify() = setup.canNotify()

    override fun batteryRestricted() = setup.batteryRestricted()

    override fun renewTurboKey() = turboKeys.renew()

    override suspend fun simplifyStoredNames() = names.simplifyStored()

    override val wakeUpStatus: Flow<WakeUpStatus?> = wakeUps.status

    /** The first account backing up: v1.0 has one, the Google Photos app (docs/PLAN.md §9.3.1). */
    override fun backupLine(): Flow<BackupLine?> = backup.overviews().map { overviews ->
        val first = overviews.firstOrNull()
        when {
            first != null -> BackupLine.On(first.account.displayName, first.today)
            backup.deviceApps.isEmpty() -> null
            else -> BackupLine.Off
        }
    }
}

/** [InternetSetup] on Android: the Worker bundled in the app, deployed through Cloudflare's API. */
@ContributesBinding(AppScope::class)
@Inject
class AndroidInternetSetup(private val setup: WorkerSetup) : InternetSetup {
    override fun available() = setup.available()

    override suspend fun deploy(token: String, onStep: (SetupStep) -> Unit): SetupResult = setup.deploy(token, onStep)

    override val tokenUrl: String = WorkerSetup.TOKEN_URL
}
