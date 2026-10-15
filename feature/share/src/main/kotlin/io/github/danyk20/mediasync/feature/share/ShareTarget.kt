/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.share

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.model.DeviceRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Lists MediaSync in the share sheet only on a sending phone paired with its receiving phone: elsewhere sharing to
 * it would do nothing. Call [start] when the app starts.
 */
@Singleton
class ShareTarget @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val peers: PeerStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether sharing to MediaSync makes sense on this phone now. */
    val available: Flow<Boolean> = combine(settings.role, peers.observeAll()) { role, all ->
        role == DeviceRole.PRODUCER && all.any { it.role == DeviceRole.CONSUMER }
    }.distinctUntilChanged()

    fun start() {
        scope.launch {
            available.collect { on ->
                val state = if (on) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, ShareActivity::class.java),
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
