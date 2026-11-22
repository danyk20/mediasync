/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorDecorators
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import io.github.danyk20.mediasync.connector.folder.FolderConnectorFactory
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.datastore.DataStoreSettingsRepository
import io.github.danyk20.mediasync.core.datastore.createSettingsDataStore
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.platform.desktop.DesktopMediaSink
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.github.danyk20.mediasync.sync.engine.ReceivedItem
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Receiving into the inbox, then the copy into the user's folder, end to end on temporary folders, a real database
 * and the real folder connector. Intervals are milliseconds so a retry doesn't take minutes.
 */
class FolderBackupServiceTest {

    @TempDir
    lateinit var directory: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val producer = DeviceId("ab".repeat(DEVICE_ID_BYTES))
    private val captured = Instant.parse("2025-05-10T12:00:00Z").toEpochMilli()

    private val inbox by lazy { directory.resolve("inbox") }
    private val database by lazy {
        Room.databaseBuilder<MediaSyncDatabase>(name = directory.resolve("test.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
    private val settings by lazy {
        DataStoreSettingsRepository(
            createSettingsDataStore(scope) {
                directory.resolve("s.preferences_pb").toOkioPath()
            },
        )
    }
    private val sink by lazy { DesktopMediaSink(inbox, reserveBytes = 0) }
    private val ledger by lazy { RoomReceivedLedger(database.receivedItemDao()) }

    @AfterEach
    fun stop() {
        scope.cancel()
        database.close()
    }

    private fun service(): FolderBackupService {
        val clock = Clock.systemUTC()
        val backupLedger = DesktopBackupLedger(database.backupDao(), clock)
        val factory = AbsoluteFolderConnectorFactory(FolderConnectorFactory(ZoneOffset.UTC))
        return FolderBackupService(
            settings = settings,
            registry = ConnectorRegistry(mapOf("folder" to factory), ConnectorDecorators(clock, sleep = {})),
            orchestrator = BackupOrchestrator(backupLedger, clock),
            ledger = backupLedger,
            backups = database.backupDao(),
            received = database.receivedItemDao(),
            inbox = inbox,
            clock = clock,
            scope = scope,
            retryEvery = RETRY.milliseconds,
            checkEvery = RETRY.milliseconds,
        )
    }

    /** What the receiver does: the sink writes the file into the inbox, the ledger records where it went. */
    private fun receive(name: String, bytes: ByteArray): Path {
        val descriptor = MediaDescriptor(
            fingerprint = bytes.toByteString().md5(),
            size = bytes.size.toLong(),
            mimeType = "image/jpeg",
            displayName = name,
            meta = MediaMeta(capturedAtMillis = captured),
        )
        val file = sink.open(producer, descriptor, 0)
        file.write(bytes, 0, bytes.size)
        val location = file.commit()
        ledger.record(ReceivedItem(producer, descriptor, bytes.toByteString().sha256(), location))
        return Path.of(location)
    }

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        try {
            withTimeout(WAIT_MILLIS) { while (!check()) delay(POLL) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            error("Still waiting for: $what")
        }
    }

    private suspend fun receipts(state: BackupState) = database.backupDao().receipts("folder", state.name)

    @Test
    fun copiesAReceivedFileIntoTheChosenFolderAndThenClearsTheInbox() = runBlocking<Unit> {
        val folder = Files.createDirectory(directory.resolve("backup"))
        settings.setBackupFolder(folder.toString())
        service().start()

        val inboxFile = receive("IMG_1.jpg", byteArrayOf(1, 2, 3))

        val copy = folder.resolve("2025").resolve("05").resolve("IMG_1.jpg")
        eventually("the copy in the folder") { Files.isRegularFile(copy) }
        Files.readAllBytes(copy).toList() shouldBe listOf<Byte>(1, 2, 3)
        eventually("the inbox file to go") { !Files.exists(inboxFile) }
        eventually("a verified receipt") { receipts(BackupState.VERIFIED).size == 1 }
    }

    @Test
    fun waitsWhileTheFolderIsMissingAndCopiesOnceItAppears() = runBlocking<Unit> {
        val folder = directory.resolve("nas") // Not mounted yet: the connector never creates it.
        settings.setBackupFolder(folder.toString())
        val service = service()
        service.start()
        val inboxFile = receive("IMG_2.jpg", byteArrayOf(4, 5))

        eventually("the folder to be reported unreachable") { service.overview().first().reachable == false }
        delay(RETRY * 4L) // Several retries go by.
        Files.exists(folder) shouldBe false
        Files.exists(inboxFile) shouldBe true
        service.overview().first().waiting shouldBe 1

        Files.createDirectory(folder)

        eventually("the copy") { Files.isRegularFile(folder.resolve("2025").resolve("05").resolve("IMG_2.jpg")) }
        eventually("the inbox file to go") { !Files.exists(inboxFile) }
        eventually("the folder to be reported reachable") { service.overview().first().reachable }
        service.overview().first().copied shouldBe 1
        service.overview().first().waiting shouldBe 0
    }

    @Test
    fun aCopyStaysWhereItWasMadeWhenTheFolderChanges() = runBlocking<Unit> {
        val first = Files.createDirectory(directory.resolve("first"))
        val second = Files.createDirectory(directory.resolve("second"))
        settings.setBackupFolder(first.toString())
        service().start()
        val firstInbox = receive("A.jpg", byteArrayOf(1))
        eventually("A verified in the first folder") { receipts(BackupState.VERIFIED).size == 1 }

        settings.setBackupFolder(second.toString())
        val secondInbox = receive("B.jpg", byteArrayOf(2))

        eventually("B copied into the second folder") { Files.isRegularFile(second.resolve("2025/05/B.jpg")) }
        eventually("B's inbox file to go") { !Files.exists(secondInbox) }
        Files.exists(first.resolve("2025/05/A.jpg")) shouldBe true
        Files.exists(firstInbox) shouldBe false
        val verified = receipts(BackupState.VERIFIED).map { it.remoteId }
        verified.count { it.startsWith(first.toString()) } shouldBe 1
        verified.count { it.startsWith(second.toString()) } shouldBe 1
    }

    @Test
    fun neverDeletesAFileOutsideTheInbox() = runBlocking<Unit> {
        val folder = Files.createDirectory(directory.resolve("backup"))
        val outside = Files.write(directory.resolve("precious.jpg"), byteArrayOf(7, 7))
        settings.setBackupFolder(folder.toString())
        service().start()
        val descriptor = MediaDescriptor("fp".toByteArray().toByteString(), 2, "image/jpeg", "precious.jpg")
        ledger.record(ReceivedItem(producer, descriptor, byteArrayOf(7, 7).toByteString().sha256(), outside.toString()))

        eventually("a verified receipt") { receipts(BackupState.VERIFIED).size == 1 }

        Files.exists(outside) shouldBe true
    }

    @Test
    fun anItemWhoseInboxFileIsGoneDoesNotBlockTheOnesAfterIt() = runBlocking<Unit> {
        val folder = Files.createDirectory(directory.resolve("backup"))
        settings.setBackupFolder(folder.toString())
        val lost = receive("LOST.jpg", byteArrayOf(8))
        Files.delete(lost) // The user cleaned the inbox by hand.
        val kept = receive("KEPT.jpg", byteArrayOf(9))
        service().start()

        eventually("the second file's copy") {
            Files.isRegularFile(folder.resolve("2025").resolve("05").resolve("KEPT.jpg"))
        }
        eventually("the inbox file to go") { !Files.exists(kept) }
        receipts(BackupState.SKIPPED).size shouldBe 1
    }

    @Test
    fun copiesNothingUntilAFolderIsChosen() = runBlocking<Unit> {
        val service = service()
        service.start()
        val inboxFile = receive("IMG_4.jpg", byteArrayOf(1))

        delay(RETRY * 4L)

        Files.exists(inboxFile) shouldBe true
        service.overview().first().path shouldBe null
        receipts(BackupState.BACKED_UP).size shouldBe 0
    }

    private companion object {
        const val RETRY = 40L
        const val POLL = 20L
        const val WAIT_MILLIS = 15_000L
        const val DEVICE_ID_BYTES = 32
    }
}
