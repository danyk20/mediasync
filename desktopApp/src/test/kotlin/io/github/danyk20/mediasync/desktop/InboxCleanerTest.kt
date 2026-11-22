/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.ReceivedItem
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The inbox cleanup on its own: a file leaves the inbox only when its copy at the recorded place is identical. */
class InboxCleanerTest {

    @TempDir
    lateinit var directory: Path

    private val clock = Clock.systemUTC()
    private val producer = DeviceId("ab".repeat(DEVICE_ID_BYTES))
    private val account = AccountId("folder")
    private val inbox by lazy { Files.createDirectories(directory.resolve("inbox")) }
    private val database by lazy {
        Room.databaseBuilder<MediaSyncDatabase>(name = directory.resolve("test.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
    private val backups by lazy { DesktopBackupLedger(database.backupDao(), clock) }
    private val received by lazy { RoomReceivedLedger(database.receivedItemDao()) }
    private val cleaner by lazy { InboxCleaner(backups, database.receivedItemDao(), inbox, clock) }

    @AfterEach
    fun close() {
        database.close()
    }

    /** What the receiver does: the file goes into the inbox and is recorded as received. */
    private suspend fun received(name: String, bytes: ByteArray): Path {
        val file = Files.write(inbox.resolve(name), bytes)
        val content = bytes.toByteString()
        val descriptor = MediaDescriptor(
            fingerprint = content.md5(),
            size = bytes.size.toLong(),
            mimeType = "image/jpeg",
            displayName = name,
        )
        received.record(ReceivedItem(producer, descriptor, content.sha256(), file.toString()))
        return file
    }

    /** A receipt saying the bytes were copied to [remoteId] (a path, as the backup records it). */
    private suspend fun receipt(bytes: ByteArray, remoteId: String) {
        val id = ContentId(bytes.toByteString().sha256().hex())
        backups.record(listOf(BackupReceipt(id, account, remoteId, BackupState.BACKED_UP, backedUpAtMillis = 0)))
    }

    /** Writes [written] at [copy] and records it as the backup of [original], the item's bytes. */
    private suspend fun copyAt(copy: Path, written: ByteArray, original: ByteArray = written) {
        Files.createDirectories(copy.parent)
        Files.write(copy, written)
        receipt(original, copy.toString())
    }

    private suspend fun backedUp() = backups.receipts(account, BackupState.BACKED_UP)

    @Test
    fun anIdenticalCopyLetsTheInboxFileGo() = runBlocking<Unit> {
        val bytes = byteArrayOf(1, 2, 3)
        val file = received("IMG_1.jpg", bytes)
        copyAt(directory.resolve("backup/2025/05/IMG_1.jpg"), bytes)

        cleaner.clean(account) shouldBe 1

        Files.exists(file) shouldBe false
        backups.receipts(account, BackupState.VERIFIED).size shouldBe 1
    }

    @Test
    fun aCopyWithOneChangedByteKeepsTheInboxFile() = runBlocking<Unit> {
        val bytes = "exactly these bytes".encodeToByteArray()
        val file = received("IMG_2.jpg", bytes)
        val changed = bytes.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        copyAt(directory.resolve("backup/IMG_2.jpg"), changed, original = bytes)

        cleaner.clean(account) shouldBe 0

        Files.exists(file) shouldBe true
        backedUp().size shouldBe 1
    }

    @Test
    fun aTruncatedCopyKeepsTheInboxFile() = runBlocking<Unit> {
        val bytes = "a longer photo".encodeToByteArray()
        val file = received("IMG_3.jpg", bytes)
        copyAt(directory.resolve("backup/IMG_3.jpg"), bytes.copyOf(bytes.size - 4), original = bytes)

        cleaner.clean(account) shouldBe 0

        Files.exists(file) shouldBe true
        backedUp().size shouldBe 1
    }

    @Test
    fun aMissingCopyKeepsTheInboxFile() = runBlocking<Unit> {
        val bytes = byteArrayOf(4, 5)
        val file = received("IMG_4.jpg", bytes)
        receipt(bytes, directory.resolve("backup/IMG_4.jpg").toString())

        cleaner.clean(account) shouldBe 0

        Files.exists(file) shouldBe true
    }

    @Test
    fun anUnreachableFolderKeepsTheInboxFile() = runBlocking<Unit> {
        val bytes = byteArrayOf(6)
        val file = received("IMG_5.jpg", bytes)
        receipt(bytes, directory.resolve("unplugged-disk/2025/05/IMG_5.jpg").toString())

        cleaner.clean(account) shouldBe 0

        Files.exists(file) shouldBe true
        Files.exists(directory.resolve("unplugged-disk")) shouldBe false
    }

    @Test
    fun aRelativeRemoteIdHasNoKnownPlaceAndKeepsTheInboxFile() = runBlocking<Unit> {
        val bytes = byteArrayOf(7)
        val file = received("IMG_6.jpg", bytes)
        receipt(bytes, "2025/05/IMG_6.jpg")

        cleaner.clean(account) shouldBe 0

        Files.exists(file) shouldBe true
    }

    @Test
    fun aCopyIsCheckedWhereItWasMadeNotInAnotherFolder() = runBlocking<Unit> {
        val bytes = "copied to the first folder".encodeToByteArray()
        val file = received("IMG_7.jpg", bytes)
        val first = directory.resolve("first/2025/05/IMG_7.jpg")
        copyAt(first, bytes)
        // The second folder holds a different file at the same relative place: it must not be looked at.
        Files.createDirectories(directory.resolve("second/2025/05"))
        Files.write(directory.resolve("second/2025/05/IMG_7.jpg"), "something else".encodeToByteArray())

        cleaner.clean(account) shouldBe 1

        Files.exists(file) shouldBe false
    }

    @Test
    fun aDamagedCopyInTheFirstFolderKeepsTheInboxFileEvenIfTheSecondHoldsAGoodOne() = runBlocking<Unit> {
        val bytes = "copied to the first folder".encodeToByteArray()
        val file = received("IMG_8.jpg", bytes)
        copyAt(directory.resolve("first/2025/05/IMG_8.jpg"), bytes.copyOf(3), original = bytes)
        // An identical file in the second folder does not count: the copy that was made is the one checked.
        Files.createDirectories(directory.resolve("second/2025/05"))
        Files.write(directory.resolve("second/2025/05/IMG_8.jpg"), bytes)

        cleaner.clean(account) shouldBe 0

        Files.exists(file) shouldBe true
    }

    private companion object {
        const val DEVICE_ID_BYTES = 32
    }
}
