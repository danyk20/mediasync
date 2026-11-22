/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.sync.android.DeviceInfo
import io.github.danyk20.mediasync.sync.android.SyncRecorder
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.RangeReader
import io.github.danyk20.mediasync.sync.engine.SourceItem
import java.net.InetAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Source

/** How the computer introduces itself to phones: its host name, this app's version, and the schedule the user set. */
@SingleIn(AppScope::class)
@Inject
class DesktopDeviceInfo(private val settings: SettingsRepository) : DeviceInfo {
    private val device by lazy { LocalDevice(hostName(), AppVersion.current) }

    override fun local(): LocalDevice = runBlocking {
        device.copy(schedule = settings.syncSchedule.first(), rendezvousUrl = settings.rendezvousUrl.first())
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails to name the host, a generic name does.
    private fun hostName(): String = try {
        InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    } ?: System.getProperty("user.name").orEmpty().ifBlank { "Computer" }
}

/** The app's version, from the jar's manifest; a run from Gradle has none. */
object AppVersion {
    val current: String = DesktopDeviceInfo::class.java.getPackage()?.implementationVersion ?: "0.0.0-dev"
}
