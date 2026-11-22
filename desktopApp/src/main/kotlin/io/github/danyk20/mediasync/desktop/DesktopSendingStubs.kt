/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.feature.producer.ProducerDevice
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.SyncRecorder
import io.github.danyk20.mediasync.sync.android.TestFileState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.util.Properties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * [ProducerDevice] on a computer that sends: nothing to ask the system for (a computer reads its folders freely, and
 * a photo's location is whatever the file holds), *Sync now* runs [ProducerSync], and folders are added with the
 * folder dialog. Left out until M9.2b: scheduled syncs, hence [missedSync] is `null`. Not on a computer: Turbo
 * transfer (no direct links), mobile data, the test file.
 */
@ContributesBinding(AppScope::class)
@Inject
class DesktopProducerDevice(
    private val sync: ProducerSync,
    private val folders: DesktopFolders,
    private val picker: FolderPicker,
    recorder: DesktopSyncRecorder,
) : ProducerDevice {
    override fun canReadLibrary() = true

    override fun canReadLocation() = true

    override fun canNotify() = true

    override fun batteryRestricted() = false

    override fun syncNow(turbo: Boolean) = sync.syncNow()

    override fun missedSync(schedule: SyncSchedule): ZonedDateTime? = null

    override val syncRecord: Flow<SyncRecord> = recorder.record

    override val mobileDataSent: Flow<Long> = flowOf(0L)

    override val testFile: Flow<TestFileState> = flowOf(TestFileState())

    override fun removeTestFile() = Unit

    override val addsFolders: Boolean = true

    override suspend fun addFolder() {
        picker.pick(null)?.let { folders.add(it.toAbsolutePath()) }
    }
}

/**
 * Keeps the last sync across restarts, in [AppPaths.lastSync] (Android keeps it in preferences): the sending home
 * screen shows it, and the next start shows it again. A file that cannot be read counts as no sync; a sync is never
 * lost from the screen because its file could not be written.
 */
@SingleIn(AppScope::class)
@Inject
class DesktopSyncRecorder(paths: AppPaths) : SyncRecorder {
    private val file = paths.lastSync
    private val _record = MutableStateFlow(read())

    val record: StateFlow<SyncRecord> = _record.asStateFlow()

    override fun recordSync(atMillis: Long, waitingForSpace: Int) {
        val next = SyncRecord(lastSyncMillis = atMillis, waitingForSpace = waitingForSpace)
        try {
            write(next)
        } catch (_: IOException) {
            // Shown until the app quits; the next sync writes the file again.
        }
        _record.value = next
    }

    /** Forgets the last sync, as *Reset MediaSync* does. */
    fun forget() {
        Files.deleteIfExists(file)
        _record.value = SyncRecord()
    }

    private fun read(): SyncRecord = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching SyncRecord()
        val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
        SyncRecord(
            lastSyncMillis = properties.getProperty(LAST_SYNC)?.toLongOrNull(),
            waitingForSpace = properties.getProperty(WAITING_FOR_SPACE)?.toIntOrNull() ?: 0,
        )
    }.getOrDefault(SyncRecord())

    private fun write(record: SyncRecord) {
        Files.createDirectories(file.parent)
        val properties = Properties().apply {
            record.lastSyncMillis?.let { setProperty(LAST_SYNC, it.toString()) }
            setProperty(WAITING_FOR_SPACE, record.waitingForSpace.toString())
        }
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, null) }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val LAST_SYNC = "last_sync_millis"
        const val WAITING_FOR_SPACE = "waiting_for_space"
    }
}
