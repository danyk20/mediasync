/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.quickaccess

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** *Sync now* in the Quick Settings panel: the label says what tapping does, the subtitle how syncing stands. */
@ExercisedOnDevice(coveredBy = "feature:quickaccess QuickAccessTest (the tile's words) and a check on an emulator")
@AndroidEntryPoint
class SyncTileService : TileService() {
    @Inject
    lateinit var quickSync: QuickSync

    private var scope: CoroutineScope? = null
    private var status: QuickStatus? = null

    override fun onStartListening() {
        scope = MainScope().also { scope ->
            scope.launch {
                quickSync.status.collect {
                    status = it
                    show(it)
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val action = status?.action ?: return
        if (!quickSync.run(action)) openApp()
    }

    private fun show(status: QuickStatus) {
        val tile = qsTile ?: return
        val labels = quickLabels(status)
        tile.label = labels.action
        tile.subtitle = labels.detail
        tile.state = when {
            status.action == QuickAction.OPEN_APP -> Tile.STATE_INACTIVE
            status.busy -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // The intent form is the only one before Android 14.
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }
}
