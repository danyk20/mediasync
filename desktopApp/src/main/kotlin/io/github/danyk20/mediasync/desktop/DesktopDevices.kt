/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.connector.android.BackupOverview
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.feature.backup.BackupDevice
import io.github.danyk20.mediasync.feature.backup.FolderBackup
import io.github.danyk20.mediasync.feature.consumer.BackupLine
import io.github.danyk20.mediasync.feature.consumer.ConsumerDevice
import io.github.danyk20.mediasync.feature.consumer.InternetSetup
import io.github.danyk20.mediasync.feature.diagnostics.DiagnosticsDevice
import io.github.danyk20.mediasync.feature.diagnostics.Release
import io.github.danyk20.mediasync.feature.diagnostics.Releases
import io.github.danyk20.mediasync.sync.android.Fact
import io.github.danyk20.mediasync.sync.android.SetupFailure
import io.github.danyk20.mediasync.sync.android.SetupResult
import io.github.danyk20.mediasync.sync.android.SetupStep
import io.github.danyk20.mediasync.sync.android.TestFileState
import io.github.danyk20.mediasync.sync.android.WakeUpStatus
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** [ConsumerDevice] on a computer: nothing to ask the system for and no wake-ups; its backup is the chosen folder. */
@ContributesBinding(AppScope::class)
@Inject
class DesktopConsumerDevice(private val folder: FolderBackupService) : ConsumerDevice {
    override fun canNotify() = true

    override fun batteryRestricted() = false

    override fun renewTurboKey() = Unit

    override suspend fun simplifyStoredNames() = 0

    override val wakeUpStatus: Flow<WakeUpStatus?> = flowOf(null)

    override fun backupLine(): Flow<BackupLine?> = folder.overview().map { overview ->
        val path = overview.path
        if (path == null) BackupLine.Off else BackupLine.Folder(folderName(path), overview.today, overview.reachable)
    }

    private fun folderName(path: String): String = Paths.get(path).fileName?.toString() ?: path
}

/** [InternetSetup] on a computer: not in this version. */
@ContributesBinding(AppScope::class)
@Inject
class DesktopInternetSetup : InternetSetup {
    override fun available() = false

    override suspend fun deploy(token: String, onStep: (SetupStep) -> Unit): SetupResult =
        SetupResult.Failed(SetupFailure.NOT_ALLOWED, "Internet sync is not available on a computer yet")

    override val tokenUrl: String = ""
}

/**
 * [BackupDevice] on a computer: no other apps to back up through; the backup is a folder the user chooses, which
 * [FolderBackupService] copies into.
 */
@ContributesBinding(AppScope::class)
@Inject
class DesktopBackupDevice(
    private val service: FolderBackupService,
    private val picker: FolderPicker,
    private val settings: SettingsRepository,
) : BackupDevice {
    override val deviceApps: List<DeviceAppConnectorFactory> = emptyList()

    override fun isInstalled(packageName: String) = false

    override fun overviews(): Flow<List<BackupOverview>> = flowOf(emptyList())

    override suspend fun connect(factory: BackupConnectorFactory) = Unit

    override suspend fun disconnect(account: AccountId) = Unit

    override fun folderBackup(): Flow<FolderBackup?> = service.overview()

    override suspend fun chooseFolder() {
        val current = settings.backupFolder.first()?.let(::fileOf)
        picker.pick(current)?.let { settings.setBackupFolder(it.toAbsolutePath().toString()) }
    }

    override suspend fun stopFolder() = settings.setBackupFolder(null)
}

/** [DiagnosticsDevice] on a computer: where it keeps things, and its log. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class DesktopDiagnosticsDevice(private val paths: AppPaths, private val log: FileDiagnosticLog) : DiagnosticsDevice {
    override val testFile: StateFlow<TestFileState> = MutableStateFlow(TestFileState())

    override suspend fun facts(): List<Fact> = listOf(
        Fact("System", "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
        Fact("Java", System.getProperty("java.version").orEmpty()),
        Fact("MediaSync", AppVersion.current),
        Fact("Data folder", paths.root.toString()),
    )

    override suspend fun recent(count: Int): List<String> = withContext(Dispatchers.IO) { log.recent(count) }

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        facts().joinToString("\n") { "${it.label}: ${it.value}" } + "\n\n" + log.read()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { log.clear() }

    override fun addTestFile(bytes: Long) = Unit

    override fun removeTestFile() = Unit
}

/** [Releases] on a computer: this copy's version; looking for newer ones comes with the installers. */
@ContributesBinding(AppScope::class)
@Inject
class DesktopReleases : Releases {
    override fun fromPlay() = false

    override fun installed(): String = AppVersion.current

    override suspend fun latest(): Release? = null
}
