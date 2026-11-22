/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.android.PairingResult
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A sending and a receiving computer in one JVM, over loopback only (no mDNS): the receiver shows its pairing link,
 * the sender pastes it, the receiver's user allows it, and then the sender sends a folder once and nothing twice.
 * Each graph has its own temporary folder; both are shut down before JUnit deletes them.
 */
class DesktopSendReceiveTest {

    @TempDir
    lateinit var directory: Path

    private val graphs = TestGraphs()

    @AfterEach
    fun shutDownGraphs() = graphs.shutDown()

    /** A file the source takes for a JPEG, old enough not to count as still being written. */
    private fun photo(name: String, fill: Int): Path = directory.resolve("photos").resolve(name).also {
        Files.createDirectories(it.parent)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        Files.write(it, jpeg + ByteArray(JPEG_BODY_BYTES) { fill.toByte() })
        Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-02-02T08:00:00Z")))
    }

    /** Every file in the receiver's inbox, by its path inside the inbox, with its bytes. */
    private fun inboxFiles(receiver: DesktopGraph): Map<String, List<Byte>> =
        Files.walk(receiver.paths.inbox).use { walk ->
            walk.filter { Files.isRegularFile(it) }
                .toList()
                .associate { receiver.paths.inbox.relativize(it).toString() to it.readBytes().toList() }
        }

    /** How many photos the receiver has recorded as received, from its ledger's database. */
    private suspend fun receivedCount(receiver: DesktopGraph): Int =
        receiver.database.receivedItemDao().observeCount().first()

    @Test
    fun `a sending computer pairs by the pasted link, sends a folder once, and sends nothing twice`() =
        runBlocking<Unit> {
            val sender = graphs.create(directory.resolve("sender"), loopback = true)
            val receiver = graphs.create(directory.resolve("receiver"), loopback = true)
            sender.settings.setRole(DeviceRole.PRODUCER)
            receiver.settings.setRole(DeviceRole.CONSUMER)
            val a = photo("a.jpg", fill = 1)
            val b = photo("b.jpg", fill = 2)
            sender.folders.add(directory.resolve("photos"))

            // The receiver listens (no mDNS needed) and makes the link its pairing screen's "Copy link" copies.
            receiver.receiver.hold("test")
            withTimeout(TIMEOUT_MILLIS) { receiver.receiver.state.first { it.listening } }
            val text = receiver.receiver.openPairing().shouldNotBeNull().toString()
            text.startsWith("mediasync://pair?") shouldBe true

            // The sender pastes it, as the pairing screen's paste does, and the receiver's user allows the pairing.
            val approval = launch(Dispatchers.IO) {
                withTimeoutOrNull(TIMEOUT_MILLIS) { receiver.receiver.pendingApproval.first { it != null } }?.accept()
            }
            val link = PairingLink.parse(text).shouldNotBeNull()
            val pairing = withTimeout(TIMEOUT_MILLIS) { sender.producerSync.pair(link) }
            pairing.shouldBeInstanceOf<PairingResult.Paired>()
            approval.join()

            val first = withTimeout(TIMEOUT_MILLIS) { sender.producerSync.syncAndWait() }
            val firstResult = first.shouldBeInstanceOf<ProducerState.Idle>().lastResult.shouldNotBeNull()
            firstResult.sent shouldBe 2
            val inbox = inboxFiles(receiver)
            inbox.values.toSet() shouldBe setOf(a.readBytes().toList(), b.readBytes().toList())
            val ledger = receivedCount(receiver)
            ledger shouldBe 2

            val second = withTimeout(TIMEOUT_MILLIS) { sender.producerSync.syncAndWait() }
            val secondResult = second.shouldBeInstanceOf<ProducerState.Idle>().lastResult.shouldNotBeNull()
            secondResult.sent shouldBe 0
            secondResult.bytesSent shouldBe 0L
            inboxFiles(receiver) shouldBe inbox
            receivedCount(receiver) shouldBe ledger

            // A renamed photo is the same photo: still nothing is sent, and the receiver gets nothing new.
            val aBytes = a.readBytes().toList()
            Files.move(a, a.resolveSibling("renamed.jpg"))
            val third = withTimeout(TIMEOUT_MILLIS) { sender.producerSync.syncAndWait() }
            third.shouldBeInstanceOf<ProducerState.Idle>().lastResult.shouldNotBeNull().sent shouldBe 0
            inboxFiles(receiver) shouldBe inbox
            receivedCount(receiver) shouldBe ledger

            // Positive control: a genuinely new photo is sent, and only it.
            val c = photo("c.jpg", fill = 3)
            val fourth = withTimeout(TIMEOUT_MILLIS) { sender.producerSync.syncAndWait() }
            fourth.shouldBeInstanceOf<ProducerState.Idle>().lastResult.shouldNotBeNull().sent shouldBe 1
            inboxFiles(receiver).values.toSet() shouldBe setOf(aBytes, b.readBytes().toList(), c.readBytes().toList())
            inboxFiles(receiver).size shouldBe 3
            receivedCount(receiver) shouldBe ledger + 1
        }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
        const val JPEG_BODY_BYTES = 300
    }
}
