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
 * folder dialog. Scheduled syncs run in [SenderScheduler], which also tells [missedSync]. Not on a computer: Turbo
 * transfer (no direct links), mobile data, the test file.
 */
@ContributesBinding(AppScope::class)
@Inject
class DesktopProducerDevice(
    private val sync: ProducerSync,
    private val folders: DesktopFolders,
    private val picker: FolderPicker,
    private val scheduler: SenderScheduler,
    recorder: DesktopSyncRecorder,
) : ProducerDevice {
    override fun canReadLibrary() = true

    override fun canReadLocation() = true

    override fun canNotify() = true

    override fun batteryRestricted() = false

    override fun syncNow(turbo: Boolean) = sync.syncNow()

    override fun missedSync(schedule: SyncSchedule): ZonedDateTime? = scheduler.missedSync(schedule)

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
 * screen shows it, and the next start shows it again. It also keeps the due time the scheduler handled last
 * ([markHandled]), in the same file, so a restart neither repeats nor skips a scheduled sync. A file that cannot be
 * read counts as no sync; a sync is never lost from the screen because its file could not be written.
 */
@SingleIn(AppScope::class)
@Inject
class DesktopSyncRecorder(paths: AppPaths) : SyncRecorder {
    private val file = paths.lastSync
    private val stored = read()
    private val _record = MutableStateFlow(stored.record)

    @Volatile
    private var handled: Long? = stored.handled

    val record: StateFlow<SyncRecord> = _record.asStateFlow()

    /** The due time handled last, in millis; `null` when none. */
    fun handledMillis(): Long? = handled

    @Synchronized
    override fun recordSync(atMillis: Long, waitingForSpace: Int) {
        val next = SyncRecord(lastSyncMillis = atMillis, waitingForSpace = waitingForSpace)
        try {
            write(next, handled)
        } catch (_: IOException) {
            // Shown until the app quits; the next sync writes the file again.
        }
        _record.value = next
    }

    /** Keeps [dueMillis] as handled, in the file; never moves back. Unwritable, it is kept until quit. */
    @Synchronized
    fun markHandled(dueMillis: Long) {
        if (dueMillis <= (handled ?: Long.MIN_VALUE)) return
        handled = dueMillis
        try {
            write(_record.value, dueMillis)
        } catch (_: IOException) {
            // Kept in memory: the next save writes the file again.
        }
    }

    /** Forgets the last sync and the handled due time, as *Reset MediaSync* does. */
    @Synchronized
    fun forget() {
        Files.deleteIfExists(file)
        handled = null
        _record.value = SyncRecord()
    }

    private fun read(): Stored = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching Stored(SyncRecord(), null)
        val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
        Stored(
            record = SyncRecord(
                lastSyncMillis = properties.getProperty(LAST_SYNC)?.toLongOrNull(),
                waitingForSpace = properties.getProperty(WAITING_FOR_SPACE)?.toIntOrNull() ?: 0,
            ),
            handled = properties.getProperty(HANDLED)?.toLongOrNull(),
        )
    }.getOrDefault(Stored(SyncRecord(), null))

    private fun write(record: SyncRecord, handled: Long?) {
        Files.createDirectories(file.parent)
        val properties = Properties().apply {
            record.lastSyncMillis?.let { setProperty(LAST_SYNC, it.toString()) }
            setProperty(WAITING_FOR_SPACE, record.waitingForSpace.toString())
            handled?.let { setProperty(HANDLED, it.toString()) }
        }
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, null) }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** What the file holds: the last sync, and the due time handled last. */
    private class Stored(val record: SyncRecord, val handled: Long?)

    private companion object {
        const val LAST_SYNC = "last_sync_millis"
        const val WAITING_FOR_SPACE = "waiting_for_space"
        const val HANDLED = "handled_due_millis"
    }
}
