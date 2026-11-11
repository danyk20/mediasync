/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.IncomingTestFile
import io.github.danyk20.mediasync.sync.engine.TestFile
import io.github.danyk20.mediasync.sync.engine.TestFileHeld
import io.github.danyk20.mediasync.sync.engine.TestFileOutcome
import io.github.danyk20.mediasync.sync.engine.TestFilePath
import io.github.danyk20.mediasync.sync.engine.TestFileQueue
import io.github.danyk20.mediasync.sync.engine.TestFileStatus
import io.github.danyk20.mediasync.sync.engine.TestFileStore
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The test file, as both phones' *Diagnostics* show it (docs/PLAN.md §7, *Test file*): on a sending phone, the one
 * [waiting] to be sent and, while it keeps waiting, the [problem] why; on both, the [last] one checked, at
 * [lastAtMillis].
 */
data class TestFileState(
    val waiting: TestFile? = null,
    val problem: TestFileHeld? = null,
    val last: TestFileOutcome? = null,
    val lastAtMillis: Long? = null,
)

/**
 * Test files (docs/PLAN.md §7, *Test file*), kept across restarts: a sync may run in the background, long after the
 * user added one.
 *
 * - On a sending phone: the test file waiting to be sent first in the next sync, its size and seed only. Its bytes
 *   are made while they're sent, so it takes no space here.
 * - On a receiving phone: where test files are written while they're checked: a folder in the app's own storage that
 *   isn't backed up, never the gallery. Each is deleted once checked, or once its sync drops; what a crash left there
 *   goes the first time it's used.
 */
@Singleton
class TestFiles @Inject constructor(@ApplicationContext context: Context) :
    TestFileQueue,
    TestFileStore {
    private val preferences = context.getSharedPreferences("test_file", Context.MODE_PRIVATE)
    private val folder = File(context.noBackupFilesDir, FOLDER)
    private val cleaned = AtomicBoolean(false)
    private val _state = MutableStateFlow(read())

    val state: StateFlow<TestFileState> = _state.asStateFlow()

    /** Adds a test file of [bytes] to send first in the next sync, in place of one waiting already. */
    fun add(bytes: Long) {
        val file = TestFile(bytes, SecureRandom().nextLong())
        write {
            putLong(WAITING_SIZE, file.size)
            putLong(WAITING_SEED, file.seed)
            remove(PROBLEM)
        }
        Diagnostics.log(Area.SEND, "Test file of ${bytes(bytes)} added; it goes first in the next sync")
    }

    /** The user removed the test file before it was sent. */
    fun remove() {
        if (_state.value.waiting == null) return
        write { clearWaiting() }
        Diagnostics.log(Area.SEND, "Test file removed before it was sent")
    }

    override fun waiting(): TestFile? = _state.value.waiting

    override fun checked(file: TestFile, outcome: TestFileOutcome) {
        write {
            if (_state.value.waiting == file) clearWaiting() // Unless the user added another one meanwhile.
            putLast(outcome)
        }
        Diagnostics.log(Area.SEND, "Test file checked by the receiving phone and deleted there: ${outcome.describe()}")
    }

    override fun held(file: TestFile, reason: TestFileHeld) {
        Diagnostics.log(Area.SEND, "Test file not sent in this sync: ${reason.describe()}; it keeps waiting")
        if (reason == TestFileHeld.STOPPED || _state.value.waiting != file) return
        write { putString(PROBLEM, reason.name) }
    }

    override fun open(): IncomingTestFile {
        cleanLeftovers()
        folder.mkdirs()
        return Incoming(File.createTempFile("test-", ".bin", folder))
    }

    override fun checked(producer: DeviceId, outcome: TestFileOutcome) {
        write { putLast(outcome) }
        Diagnostics.log(Area.RECEIVE, "Test file from ${producer.short()} checked and deleted: ${outcome.describe()}")
    }

    /** Once a run. */
    override fun cleanLeftovers() {
        if (!cleaned.compareAndSet(false, true)) return
        val left = folder.listFiles().orEmpty()
        left.forEach { it.delete() }
        if (left.isNotEmpty()) Diagnostics.log(Area.RECEIVE, "Deleted ${left.size} test file(s) left from before")
    }

    @Synchronized
    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        preferences.edit(action = change)
        _state.value = read()
    }

    private fun SharedPreferences.Editor.clearWaiting() {
        remove(WAITING_SIZE)
        remove(WAITING_SEED)
        remove(PROBLEM)
    }

    private fun SharedPreferences.Editor.putLast(outcome: TestFileOutcome) {
        putString(LAST_STATUS, outcome.status.name)
        putLong(LAST_SIZE, outcome.size)
        putLong(LAST_BYTES, outcome.bytes)
        putLong(LAST_MICROS, outcome.micros)
        putString(LAST_PATH, outcome.path.name)
        putLong(LAST_AT, System.currentTimeMillis())
    }

    /** What a newer app version wrote and this one doesn't know reads as none. */
    private fun read(): TestFileState {
        val size = preferences.getLong(WAITING_SIZE, 0)
        val status = preferences.getString(LAST_STATUS, null)?.let { name ->
            TestFileStatus.entries.firstOrNull { it.name == name }
        }
        val path = preferences.getString(LAST_PATH, null)?.let { name ->
            TestFilePath.entries.firstOrNull { it.name == name }
        }
        val last = if (status != null && path != null) {
            TestFileOutcome(
                status,
                preferences.getLong(LAST_SIZE, 0),
                preferences.getLong(LAST_BYTES, 0),
                preferences.getLong(LAST_MICROS, 0),
                path,
            )
        } else {
            null
        }
        return TestFileState(
            waiting = if (size > 0) TestFile(size, preferences.getLong(WAITING_SEED, 0)) else null,
            problem = preferences.getString(PROBLEM, null)?.let { name ->
                TestFileHeld.entries.firstOrNull { it.name == name }
            },
            last = last,
            lastAtMillis = last?.let { preferences.getLong(LAST_AT, 0) },
        )
    }

    /** A test file arriving: written to [file], which only MediaSync can read. */
    private class Incoming(private val file: File) : IncomingTestFile {
        private val output = FileOutputStream(file)

        override fun write(bytes: ByteArray, offset: Int, byteCount: Int) = output.write(bytes, offset, byteCount)

        override fun delete() {
            close()
            if (file.exists() && !file.delete()) Diagnostics.log(Area.RECEIVE, "Couldn't delete a test file")
        }

        override fun close() = output.close()
    }

    private companion object {
        const val FOLDER = "test-files"
        const val WAITING_SIZE = "waiting_size"
        const val WAITING_SEED = "waiting_seed"
        const val PROBLEM = "problem"
        const val LAST_STATUS = "last_status"
        const val LAST_SIZE = "last_size"
        const val LAST_BYTES = "last_bytes"
        const val LAST_MICROS = "last_micros"
        const val LAST_PATH = "last_path"
        const val LAST_AT = "last_at"
    }
}

/** How a test file arrived, for the diagnostic log. */
internal fun TestFileOutcome.describe(): String {
    val how = when (status) {
        TestFileStatus.MATCHED -> "arrived exactly as sent"
        TestFileStatus.MISMATCHED -> "didn't arrive as sent"
        TestFileStatus.NOT_STORED -> "couldn't be written there"
    }
    val over = when (path) {
        TestFilePath.WIFI -> "Wi-Fi"
        TestFilePath.TURBO -> "Turbo"
        TestFilePath.INTERNET -> "the internet"
        TestFilePath.RELAY -> "the internet, through the relay"
    }
    return "$how, ${bytes(bytes)} of ${bytes(size)} in ${since(0, micros / MICROS_PER_MILLI)} " +
        "(${String.format(Locale.ROOT, "%.1f", megabytesPerSecond)} MB/s) over $over"
}

private fun TestFileHeld.describe(): String = when (this) {
    TestFileHeld.RECEIVER_OUTDATED -> "the receiving phone's MediaSync is too old for test files"
    TestFileHeld.NO_SPACE -> "it doesn't fit in the receiving phone's free space"
    TestFileHeld.NOT_WRITABLE -> "the receiving phone couldn't write it"
    TestFileHeld.STOPPED -> "the sync stopped"
}

private const val MICROS_PER_MILLI = 1000L
