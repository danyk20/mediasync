/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.createGraphFactory
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SendRange
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.platform.desktop.DesktopIdentityProvider
import io.github.danyk20.mediasync.platform.desktop.FileSecretStore
import io.github.danyk20.mediasync.platform.desktop.FolderMediaLibrary
import io.github.danyk20.mediasync.platform.desktop.FolderMediaSource
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The computer as a sender: the role, its folders and fingerprints, reset and quitting, on temporary folders only. */
class DesktopSenderTest {

    @TempDir
    lateinit var directory: Path

    private fun graph() =
        createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory.resolve("data"), isolated = true))

    /** A file the source takes for a JPEG, old enough not to count as still being written. */
    private fun photo(name: String): Path = directory.resolve("photos").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(300) { 1 })
        Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-02-02T08:00:00Z")))
    }

    private fun scope() = CoroutineScope(Job())

    private companion object {
        /** Longer than the wait between two tries for the keychain (ten seconds). */
        const val WAIT_MILLIS = 30_000L
    }

    @Test
    fun `the role is chosen once and written to the settings file`() = runBlocking<Unit> {
        val graph = graph()
        graph.settings.role.first().shouldBeNull()

        graph.settings.setRole(DeviceRole.PRODUCER)

        graph.settings.role.first() shouldBe DeviceRole.PRODUCER
        String(Files.readAllBytes(graph.paths.settings)).contains("PRODUCER") shouldBe true
        AppShutdown(graph.shutdownSteps()).run()
    }

    @Test
    fun `a sending computer sends from folders, and offers none until the user adds one`() = runBlocking<Unit> {
        val graph = graph()
        photo("a.jpg")
        graph.mediaSource.shouldBeInstanceOf<FolderMediaSource>()
        graph.mediaSource.items().shouldBeEmpty()
        graph.mediaLibrary.folders().shouldBeEmpty()

        graph.folders.add(directory.resolve("photos"))

        graph.mediaSource.items().map { it.descriptor.displayName } shouldBe listOf("a.jpg")
        graph.mediaLibrary.folders().map { it.itemCount } shouldBe listOf(1)
    }

    @Test
    fun `an unchecked folder stays on the list, unchecked, until it is gone from the disk`() = runBlocking<Unit> {
        val graph = graph()
        photo("a.jpg")
        val folder = directory.resolve("photos")
        graph.folders.add(folder)

        graph.settings.setSourceSelection(SourceSelection.of(emptySet())) // The user unchecks it.

        graph.mediaSource.items().shouldBeEmpty()
        graph.folders.chosen().shouldBeEmpty()
        graph.mediaLibrary.folders().map { it.path } shouldBe listOf(FolderMediaLibrary.folderPath(folder))
        graph.mediaLibrary.folders().single().itemCount shouldBe 1

        folder.toFile().deleteRecursively()

        graph.mediaLibrary.folders().shouldBeEmpty()
        AppShutdown(graph.shutdownSteps()).run()
    }

    @Test
    fun `a sending computer runs no receiver, no folder backup and no announcement`() = runBlocking<Unit> {
        val graph = graph()
        graph.settings.setRole(DeviceRole.PRODUCER)
        val scope = scope()

        graph.roles.apply(DeviceRole.PRODUCER, scope)

        graph.roles.active shouldBe DeviceRole.PRODUCER
        graph.receiver.state.value.listening shouldBe false
        graph.receiving.state.value.shouldBeNull()
        graph.roles.stopAll()
        scope.coroutineContext[Job]!!.cancel()
        AppShutdown(graph.shutdownSteps()).run()
    }

    @Test
    fun `quitting before any role was chosen, or as a sender, does not fail`() = runBlocking<Unit> {
        val none = graph()
        none.roles.stopAll()
        none.receiver.stop()
        AppShutdown(none.shutdownSteps()).run()

        val sender = graph()
        sender.roles.apply(DeviceRole.PRODUCER, scope())
        AppShutdown(sender.shutdownSteps()).run()
        sender.roles.active.shouldBeNull()
    }

    /** A source like the graph's, over the folder of [photo], with the graph's database as the cache. */
    private fun sourceOver(graph: DesktopGraph) = FolderMediaSource(
        folders = { setOf(directory.resolve("photos")) },
        cache = RoomFingerprintCache(graph.database.mediaFingerprintDao()),
    )

    @Test
    fun `fingerprints are kept in the database and read from it after a restart`() = runBlocking<Unit> {
        val file = photo("a.jpg")
        val first = graph()
        val fingerprint = sourceOver(first).items().single().descriptor.fingerprint
        val dao = first.database.mediaFingerprintDao()
        val row = dao.get(file.toString()).shouldNotBeNull()
        row.fingerprint shouldBe fingerprint.hex()
        // Forge the kept value: a restarted app must take it from the database without reading the file.
        val forged = okio.ByteString.of(*ByteArray(fingerprint.size) { 9 })
        dao.upsert(row.copy(fingerprint = forged.hex()))
        AppShutdown(first.shutdownSteps()).run()

        val second = graph()

        sourceOver(second).items().single().descriptor.fingerprint shouldBe forged
        AppShutdown(second.shutdownSteps()).run()
    }

    @Test
    fun `a file changed since its fingerprint was kept is fingerprinted again`() = runBlocking<Unit> {
        val file = photo("a.jpg")
        val graph = graph()
        val before = sourceOver(graph).items().single().descriptor.fingerprint
        val dao = graph.database.mediaFingerprintDao()
        dao.upsert(dao.get(file.toString())!!.copy(modifiedAtMillis = 1L, fingerprint = "00"))

        sourceOver(graph).items().single().descriptor.fingerprint shouldBe before
        dao.get(file.toString())!!.modifiedAtMillis shouldBe Files.getLastModifiedTime(file).toMillis()
        AppShutdown(graph.shutdownSteps()).run()
    }

    @Test
    fun `a sending computer waits for a locked keychain, then runs once it opens`() = runBlocking<Unit> {
        val paths = AppPaths(directory.resolve("data"), isolated = true)
        // The identity is made while the keychain is open; then its password is out of reach, as when it is locked.
        DesktopIdentityProvider(paths.identity, FileSecretStore(paths.identity.resolve("password"))).identity()
        val password = paths.identity.resolve("password")
        val kept = paths.identity.resolve("password.kept")
        Files.move(password, kept)
        val graph = createGraphFactory<DesktopGraph.Factory>().create(paths)
        graph.settings.setRole(DeviceRole.PRODUCER)

        graph.roles.apply(DeviceRole.PRODUCER, scope())
        withTimeout(WAIT_MILLIS) { graph.sending.state.first { it == StartState.WAITING_FOR_KEYCHAIN } }

        Files.move(kept, password) // The keychain opens.
        withTimeout(WAIT_MILLIS) { graph.sending.state.first { it == StartState.RUNNING } }
        graph.roles.stopAll()
        AppShutdown(graph.shutdownSteps()).run()
    }

    @Test
    fun `the last sync is shown again after a restart, and reset forgets it`() = runBlocking<Unit> {
        val first = graph()
        first.syncRecorder.recordSync(atMillis = 1_780_000_000_000L, waitingForSpace = 2)
        AppShutdown(first.shutdownSteps()).run()

        val restarted = graph()
        restarted.syncRecorder.record.first() shouldBe
            SyncRecord(lastSyncMillis = 1_780_000_000_000L, waitingForSpace = 2)

        restarted.reset.reset()

        restarted.syncRecorder.record.first() shouldBe SyncRecord()
        AppShutdown(restarted.shutdownSteps()).run()
    }

    @Test
    fun `reset forgets the role, pairings, folders, days, schedule, settings and history and keeps the photos`() =
        runBlocking<Unit> {
            val graph = graph()
            photo("a.jpg")
            val identity = graph.identity.identity().id
            val received = Files.createDirectories(graph.paths.inbox).resolve("kept.jpg")
                .also { Files.write(it, byteArrayOf(1, 2, 3)) }
            graph.settings.setRole(DeviceRole.PRODUCER)
            graph.folders.add(directory.resolve("photos"))
            graph.mediaSource.items().size shouldBe 1 // Writes the fingerprint of the photo to the database.
            graph.settings.setSendRange(SendRange(from = LocalDate(2026, 1, 1)))
            graph.settings.setSyncSchedule(SyncSchedule.Daily(LocalTime(9, 30)))
            graph.settings.setBackupFolder(directory.resolve("backup").toString())
            graph.peerStore.register(PairedPeer(DeviceId("a".repeat(64)), "Home server", DeviceRole.CONSUMER))

            graph.reset.reset()

            graph.settings.role.first().shouldBeNull()
            graph.settings.sourceSelection.first() shouldBe SourceSelection.Default
            graph.settings.sendRange.first() shouldBe SendRange.Everything
            graph.settings.syncSchedule.first() shouldBe SyncSchedule.Default
            graph.settings.backupFolder.first().shouldBeNull()
            graph.peerStore.observeAll().first().shouldBeEmpty()
            graph.folders.chosen().shouldBeEmpty()
            graph.database.mediaFingerprintDao().get(directory.resolve("photos/a.jpg").toString()).shouldBeNull()
            Files.readAllBytes(received) shouldBe byteArrayOf(1, 2, 3)
            graph.identity.identity().id shouldBe identity
            AppShutdown(graph.shutdownSteps()).run()
        }
}
