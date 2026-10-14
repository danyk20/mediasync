/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.diagnostics.Sanitizer
import io.github.danyk20.mediasync.core.model.DeviceRole
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One line of the report: what, and how it is. */
data class Fact(val label: String, val value: String)

/**
 * What someone helping with a problem needs to know besides the log (docs/PLAN.md §3.3, *Diagnostics*): this phone,
 * how MediaSync is set up on it, what Android allows it, and the state of its background work. Like the log, it
 * names no files, places or people.
 */
@Singleton
class DiagnosticsReport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val peers: PeerStore,
    private val history: SyncHistory,
    private val network: AddressProvider,
    private val workManager: WorkManager,
    private val log: FileDiagnosticLog,
    private val clock: Clock,
) {
    private val setup = BackgroundSetup(context)

    suspend fun facts(): List<Fact> = withContext(Dispatchers.IO) {
        val role = settings.role.first()
        buildList {
            add(Fact("App", "MediaSync ${version()}"))
            add(Fact("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            add(Fact("Phone", "${Build.MANUFACTURER} ${Build.MODEL}"))
            add(Fact("Role", role?.name?.lowercase() ?: "not chosen"))
            addAll(roleFacts(role))
            add(Fact("Battery use", if (setup.batteryRestricted()) "optimized (restricted)" else "not restricted"))
            add(Fact("Notifications", if (setup.canNotify()) "allowed" else "not allowed"))
            add(Fact("Power", power()))
            val addresses = network.localAddresses().map { Sanitizer.clean(it.hostAddress.orEmpty()) }
            add(Fact("Local network", addresses.joinToString().ifEmpty { "none" }))
            add(Fact("Free space", bytes(StatFs(Environment.getExternalStorageDirectory().path).availableBytes)))
            SyncScheduler.WORK_NAMES.forEach { name -> add(Fact("Work: $name", work(name))) }
            add(Fact("Report made", TIME.format(ZonedDateTime.now(clock))))
        }
    }

    /** The report and the whole log, as one text to share. */
    suspend fun export(): String {
        val facts = facts().joinToString("\n") { "${it.label}: ${it.value}" }
        val entries = withContext(Dispatchers.IO) { log.read() }
        return "MediaSync diagnostics\n\n$facts\n\nLog (oldest first)\n\n$entries"
    }

    suspend fun recent(count: Int): List<String> = withContext(Dispatchers.IO) { log.recent(count) }

    suspend fun clear() = withContext(Dispatchers.IO) { log.clear() }

    private suspend fun roleFacts(role: DeviceRole?): List<Fact> = when (role) {
        DeviceRole.PRODUCER -> {
            val consumer = peers.consumer()
            val record = history.record.value
            listOf(
                Fact("Sends to", consumer?.id?.short() ?: "not paired"),
                Fact("Schedule", (consumer?.schedule?.encode() ?: "daily@03:00") + " (the receiving phone's)"),
                Fact("Photo details", settings.photoDetails.first().name.lowercase()),
                Fact("Last sync", record.lastSyncMillis?.let { at(it) } ?: "never"),
                Fact("Last problem", record.problem?.name?.lowercase() ?: "none"),
            )
        }

        DeviceRole.CONSUMER -> listOf(
            Fact("Receives from", peers.observeAll().first().count { it.role == DeviceRole.PRODUCER }.toString()),
            Fact("Schedule", settings.syncSchedule.first().encode()),
        )

        null -> emptyList()
    }

    private suspend fun work(name: String): String {
        val info = workManager.getWorkInfosForUniqueWorkFlow(name).first().lastOrNull() ?: return "none"
        val parts = mutableListOf(info.state.name.lowercase(), "try ${info.runAttemptCount + 1}")
        if (info.state == WorkInfo.State.ENQUEUED && info.nextScheduleTimeMillis < Long.MAX_VALUE) {
            parts += "next at ${at(info.nextScheduleTimeMillis)}"
        }
        if (info.stopReason != WorkInfo.STOP_REASON_NOT_STOPPED) parts += "last stopped: ${stopReason(info.stopReason)}"
        return parts.joinToString(", ")
    }

    private fun power(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val percent = if (level >= 0 && scale > 0) "${level * PERCENT / scale} %" else "battery unknown"
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        return "$percent, ${if (plugged) "charging" else "not charging"}, thermal status $thermal"
    }

    private fun version(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }

    /** With the UTC offset only: a time zone's name would hint at where the phone is. */
    private fun at(millis: Long) = TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), clock.zone))

    private companion object {
        const val PERCENT = 100
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DiagnosticsModule {
    /** Kept in the app's own files: nothing else can read it, and uninstalling removes it. */
    @Provides
    @Singleton
    fun diagnosticLog(@ApplicationContext context: Context, clock: Clock): FileDiagnosticLog =
        FileDiagnosticLog(File(context.filesDir, "diagnostics"), clock)
}
