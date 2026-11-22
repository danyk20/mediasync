/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.platform.desktop.DesktopIdentityProvider
import io.github.danyk20.mediasync.platform.desktop.DesktopLocalNetwork
import io.github.danyk20.mediasync.platform.desktop.DesktopServiceDiscovery
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import kotlinx.coroutines.CoroutineScope

/** The desktop app's dependency graph: everything contributed to [AppScope] plus the desktop's own bindings. */
@DependencyGraph(AppScope::class)
interface DesktopGraph : ViewModelGraph {
    val receiver: ConsumerReceiver
    val identity: DesktopIdentityProvider
    val diagnosticLog: FileDiagnosticLog
    val paths: AppPaths
    val settings: SettingsRepository
    val folderBackup: FolderBackupService
    val receivedLedger: ReceivedLedger
    val discovery: DesktopServiceDiscovery
    val network: DesktopLocalNetwork
    val database: MediaSyncDatabase
    val peerStore: PeerStore
    val startAtLogin: DesktopStartAtLogin
    val viewModelFactory: MetroViewModelFactory
    val roles: RoleLifecycle
    val receiving: ReceivingPart
    val sending: SendingPart
    val senderScheduler: SenderScheduler
    val mediaSource: MediaSource
    val mediaLibrary: MediaLibrary
    val folders: DesktopFolders
    val reset: DesktopReset
    val syncRecorder: DesktopSyncRecorder
    val producerSync: ProducerSync

    /** Where the receiver, the sync and the backup copier run; cancelled at shutdown before the database closes. */
    @ApplicationScope
    val applicationScope: CoroutineScope

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides paths: AppPaths): DesktopGraph
    }
}
