/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.DeviceAppProfile
import io.github.danyk20.mediasync.connector.android.BackupOverview
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.connector.android.InstalledApps
import javax.inject.Inject
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

/** What the backup screen shows. */
data class BackupUiState(val apps: List<AppBackup> = emptyList())

/** What the backup screen can do; the defaults do nothing, for previews and tests. */
data class BackupActions(
    val openApp: (DeviceAppProfile) -> Unit = {},
    val install: (DeviceAppProfile) -> Unit = {},
    val openStorageSettings: () -> Unit = {},
    val connect: (ConnectorId) -> Unit = {},
    val disconnect: (AppBackup) -> Unit = {},
)

/** Cloud backup on the receiving phone (docs/PLAN.md §9): the apps that can back it up, and how that stands. */
@HiltViewModel
class BackupViewModel @Inject constructor(private val backup: CloudBackup, private val installedApps: InstalledApps) :
    ViewModel() {

    private val checks = MutableStateFlow(0)

    /** `null` while loading. */
    val uiState: StateFlow<BackupUiState?> = combine(backup.overviews(), checks) { overviews, _ ->
        BackupUiState(
            backup.deviceApps.map { factory ->
                AppBackup(
                    connector = factory.descriptor.id,
                    profile = factory.profile,
                    installed = installedApps.isInstalled(factory.profile.packageName),
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
}
