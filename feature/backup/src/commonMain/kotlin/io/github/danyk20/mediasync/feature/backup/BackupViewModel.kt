/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.DeviceAppProfile
import io.github.danyk20.mediasync.connector.android.BackupOverview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An app on this phone that backs up what MediaSync receives, such as Google Photos (docs/PLAN.md §9.3.1). */
data class AppBackup(
    val connector: ConnectorId,
    val profile: DeviceAppProfile,
    /** The app is on this phone. */
    val installed: Boolean,
    /** How its backups stand; `null` until the user has set it up. */
    val overview: BackupOverview? = null,
)

/** A computer's backup into a folder (docs/PLAN.md §9, M8): `path` is `null` until the user has chosen one. */
data class FolderBackup(
    val path: String? = null,
    /** The folder can be reached now: a disk or share that is unplugged or unmounted can't. */
    val reachable: Boolean = true,
    /** Copied since midnight. */
    val today: Int = 0,
    /** Copied, in all. */
    val copied: Int = 0,
    /** Received, and not copied yet. */
    val waiting: Int = 0,
)

/** What the backup screen shows; [folder] is `null` on a device that has no folder backup. */
data class BackupUiState(val apps: List<AppBackup> = emptyList(), val folder: FolderBackup? = null)

/** What the backup screen can do; the defaults do nothing, for previews and tests. */
data class BackupActions(
    val openApp: (DeviceAppProfile) -> Unit = {},
    val install: (DeviceAppProfile) -> Unit = {},
    val openStorageSettings: () -> Unit = {},
    val connect: (ConnectorId) -> Unit = {},
    val disconnect: (AppBackup) -> Unit = {},
    val chooseFolder: () -> Unit = {},
    val stopFolder: () -> Unit = {},
)

/** Cloud backup on the receiving phone (docs/PLAN.md §9): the apps that can back it up, and how that stands. */
@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class BackupViewModel(private val backup: BackupDevice) : ViewModel() {

    private val checks = MutableStateFlow(0)

    /** `null` while loading. */
    val uiState: StateFlow<BackupUiState?> = combine(
        backup.overviews(),
        backup.folderBackup(),
        checks,
    ) { overviews, folder, _ ->
        BackupUiState(
            folder = folder,
            apps = backup.deviceApps.map { factory ->
                AppBackup(
                    connector = factory.descriptor.id,
                    profile = factory.profile,
                    installed = backup.isInstalled(factory.profile.packageName),
                    overview = overviews.firstOrNull { it.account.connectorId == factory.descriptor.id },
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Call when the screen is shown again: the user may have installed the app meanwhile. */
    fun refresh() = checks.update { it + 1 }

    /** The user has set the app up: items received go to it from now on, and those before too. */
    fun connect(connector: ConnectorId) {
        val factory = backup.deviceApps.firstOrNull { it.descriptor.id == connector } ?: return
        viewModelScope.launch { backup.connect(factory) }
    }

    /** Stops handing items to [app]. What it backed up stays recorded, should it be set up again. */
    fun disconnect(app: AppBackup) {
        val account = app.overview?.account ?: return
        viewModelScope.launch { backup.disconnect(account.id) }
    }

    /** Opens the folder dialog; copying into the chosen folder starts at once. */
    fun chooseFolder() {
        viewModelScope.launch { backup.chooseFolder() }
    }

    /** Stops copying into the folder. What was copied stays there. */
    fun stopFolder() {
        viewModelScope.launch { backup.stopFolder() }
    }
}
