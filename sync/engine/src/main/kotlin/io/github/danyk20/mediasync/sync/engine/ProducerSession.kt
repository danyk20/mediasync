/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.metadata.ByteReader
import io.github.danyk20.mediasync.core.metadata.PhotoDetailsRemover
import io.github.danyk20.mediasync.core.metadata.Redaction
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendingOrder
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.FileAck
import io.github.danyk20.mediasync.sync.protocol.FileBegin
import io.github.danyk20.mediasync.sync.protocol.FileEnd
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.Offer
import io.github.danyk20.mediasync.sync.protocol.OfferItem
import io.github.danyk20.mediasync.sync.protocol.OfferResponse
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.ProtocolException
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.buffer
import okio.source

/** The outcome of one producer session. */
data class SyncResult(
    /** Transferred and confirmed by the consumer in this session. */
    val sent: Int = 0,
    /** The consumer already had them (another producer, or a lost producer ledger); nothing was transferred. */
    val alreadyOnConsumer: Int = 0,
    /** Couldn't be read here or failed verification there; offered again next time. */
    val failed: Int = 0,
    /** Not requested by the consumer, usually because it's low on space. */
    val deferred: Int = 0,
    val bytesSent: Long = 0,
    val cancelled: Boolean = false,
    /** The schedule the consumer asked its producers to follow; `null` if it didn't say (an older version). */
    val consumerSchedule: SyncSchedule? = null,
    /**
     * Not sent under the chosen *Photo details*: their details can't be removed (RAW photos under *Remove all*,
     * formats MediaSync can't read). They're sent once the setting allows it.
     */
    val withheld: Int = 0,
    /** What the consumer's *Turbo receive* links derive from (docs/PLAN.md §8.3); `null` if it didn't say. */
    val consumerTurboKey: ByteString? = null,
    /** The secret this pair's internet signaling is sealed with (ADR 0009 §4); `null` if the consumer didn't say. */
    val consumerPairSecret: ByteString? = null,
    /** The consumer's rendezvous Worker (PLAN §8.4); `null` if it has none or didn't say. */
    val consumerRendezvousUrl: String? = null,
    /** Held back for this connection, such as videos over the relay (PLAN §17, relay saver); sent another time. */
    val heldBack: Int = 0,
) {
    /** Never the key: this ends up in logs. */
    override fun toString() = "SyncResult(sent=$sent, alreadyOnConsumer=$alreadyOnConsumer, failed=$failed, " +
        "deferred=$deferred, bytesSent=$bytesSent, cancelled=$cancelled, consumerSchedule=$consumerSchedule, " +
        "withheld=$withheld, heldBack=$heldBack)"
}

/**
 * Sends everything the consumer hasn't confirmed yet, in the [order] the user chose (docs/PLAN.md §5), with the
 * [details] the user chose to send (docs/PLAN.md §6.3): removed details never leave this phone.
 *
 * The session is blocking: run it on an I/O thread. Closing the connection aborts it; everything
 * already confirmed stays recorded, and a partly sent file resumes next time.
 */
class ProducerSession(
    private val local: LocalDevice,
    private val source: MediaSource,
    private val ledger: SentLedger,
    private val progress: ProgressListener = ProgressListener {},
    private val shouldContinue: () -> Boolean = { true },
    private val details: PhotoDetails = PhotoDetails.KEEP_ALL,
    /** Resolved for the connection already: [SendingOrder.AUTOMATIC] counts as newest first. */
    private val order: SendingOrder = SendingOrder.Default,
    /**
     * Files sent ahead of the consumer's confirmation (docs/PLAN.md §7, pipelining): the next file starts while the
     * consumer still verifies and stores the previous ones, so there's no round trip per file.
     */
    private val window: Int = WINDOW,
) {

    fun sync(connection: PeerConnection): SyncResult = sync(connection, WorkQueue(pendingFor(connection.peerId)))

    /** Sends items from [queue], which other sessions on other connections may share (docs/PLAN.md §7). */
    fun sync(connection: PeerConnection, queue: WorkQueue): SyncResult {
        val channel = FrameChannel(connection)
        val consumer = connection.peerId
        channel.send(Hello(PROTOCOL_VERSION, local.name, WireRole.PRODUCER, local.appVersion))
        val hello = channel.expect<Hello>()
        if (hello.protocolVersion != PROTOCOL_VERSION || hello.role != WireRole.CONSUMER) {
            channel.send(Bye(Bye.Reason.INCOMPATIBLE_VERSION))
            throw IncompatiblePeerException("Peer speaks v${hello.protocolVersion} as role ${hello.role}")
        }

        val run = Run(connection, channel, consumer, queue.total, queue.totalBytes)
        run.consumerSchedule = SyncSchedule.decode(hello.schedule)
        run.consumerTurboKey = hello.turboKey.takeIf { it.size == TURBO_KEY_BYTES }
        run.consumerPairSecret = hello.pairSecret.takeIf { it.size == PAIR_SECRET_BYTES }
        run.consumerRendezvousUrl = hello.rendezvousUrl.takeIf { it.startsWith("https://") }
        return run.send(queue)
    }

    /** Items the consumer hasn't confirmed, without duplicates: those the user shared first, then in [order]. */
    internal fun pendingFor(consumer: DeviceId): List<SourceItem> {
        val confirmed = ledger.sentFingerprints(consumer)
        return source.items()
            .filter { it.descriptor.fingerprint !in confirmed }
            .sortedWith(compareByDescending<SourceItem> { it.shared }.then(order.comparator()))
            .distinctBy { it.descriptor.fingerprint }
    }

    /**
     * One session. This thread offers and sends; a second one reads the consumer's replies: confirmations as they
     * come, and offer responses for this thread. Counters are shared, under [lock].
     */
    private inner class Run(
        private val connection: PeerConnection,
        private val channel: FrameChannel,
        private val consumer: DeviceId,
        private val itemsTotal: Int,
        private val bytesTotal: Long,
    ) {
        private val buffer = ByteArray(CHUNK_SIZE)
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val inFlight = LinkedHashMap<Int, Prepared>()
        private val responses = LinkedBlockingQueue<Any>()
        private val replies = Thread(::readReplies, "mediasync-replies").apply { isDaemon = true }

        @Volatile
        private var failure: IOException? = null

        @Volatile
        private var finished = false
        private var nextOfferId = 0
        private var itemsDone = 0
        private var bytesDone = 0L
        private var sent = 0
        private var alreadyOnConsumer = 0
        private var failed = 0
        private var deferred = 0
        private var withheld = 0
        private var bytesSent = 0L
        private var cancelled = false
        var consumerSchedule: SyncSchedule? = null
        var consumerTurboKey: ByteString? = null
        var consumerPairSecret: ByteString? = null
        var consumerRendezvousUrl: String? = null

        fun send(queue: WorkQueue): SyncResult {
            replies.start()
            try {
                while (!cancelled) {
                    if (!shouldContinue()) cancelled = true
                    val batch = if (cancelled) emptyList() else queue.take(OFFER_BATCH_SIZE, OFFER_BATCH_BYTES)
                    if (batch.isEmpty()) break
                    offerBatch(batch)
                }
                awaitInFlight(0)
                finished = true
                channel.send(Bye(if (cancelled) Bye.Reason.CANCELLED else Bye.Reason.DONE))
            } catch (e: IOException) {
                throw failure ?: e // The replies' side usually knows why: the consumer ended or the link dropped.
            } finally {
                finished = true
            }
            return lock.withLock {
                SyncResult(
                    sent,
                    alreadyOnConsumer,
                    failed,
                    deferred,
                    bytesSent,
                    cancelled,
                    consumerSchedule,
                    withheld,
                    consumerTurboKey,
                    consumerPairSecret,
                    consumerRendezvousUrl,
                )
            }
        }

        private fun offerBatch(batch: List<SourceItem>) {
            val offered = batch.mapNotNull(::prepare).associateBy { nextOfferId++ }
            if (offered.isEmpty()) return
            channel.send(Offer(offered.map { (id, prepared) -> prepared.descriptor.toOfferItem(id) }))
            val response = awaitResponse()

            for (have in response.have) {
                val prepared =
                    offered[have.offerId] ?: throw ProtocolException("HAVE for unknown offer ${have.offerId}")
                ledger.markSent(consumer, prepared.descriptor.fingerprint, have.contentId)
                source.confirmed(prepared.item)
                count { alreadyOnConsumer++ }
                completeItem(prepared.item)
            }
            for (want in response.want) {
                if (!shouldContinue()) {
                    cancelled = true
                    return
                }
                val prepared =
                    offered[want.offerId] ?: throw ProtocolException("WANT for unknown offer ${want.offerId}")
                if (!sendFile(want.offerId, prepared, want.resumeOffset)) {
                    count { failed++ }
                    completeItem(prepared.item)
                }
            }
            count { deferred += offered.size - response.want.size - response.have.size }
        }

        /** How [item] is sent under the chosen details; `null` if it isn't, now. */
        private fun prepare(item: SourceItem): Prepared? {
            if (details == PhotoDetails.KEEP_ALL) return Prepared(item, item.descriptor, Redaction.None)
            val redaction = plan(item)
            if (redaction == null || redaction is Redaction.Withheld) {
                count { if (redaction == null) failed++ else withheld++ } // Unreadable now: offered again next time.
                completeItem(item)
                return null
            }
            val meta = item.descriptor.meta
            // The album and favorite travel beside the file; *Remove all* removes them too.
            val kept = if (details == PhotoDetails.REMOVE_ALL) meta.copy(album = null, favorite = false) else meta
            val size = (redaction as? Redaction.Edited)?.outputSize ?: item.descriptor.size
            return Prepared(item, item.descriptor.copy(size = size, meta = kept), redaction)
        }

        private fun plan(item: SourceItem): Redaction? = try {
            source.openRanges(item).use { file ->
                PhotoDetailsRemover.plan(item.descriptor.size, ByteReader(file::read), details)
            }
        } catch (_: IOException) {
            null
        }

        /** Sends the file; the consumer confirms it later ([onAck]). `false` if it couldn't be sent. */
        private fun sendFile(offerId: Int, prepared: Prepared, offset: Long): Boolean {
            if (offset !in 0..prepared.descriptor.size) throw ProtocolException("Resume offset $offset out of range")
            // Changed since it was offered: its details may have too. It's offered again next time.
            if (details != PhotoDetails.KEEP_ALL && plan(prepared.item) != prepared.redaction) return false
            // Deleted or unreadable since indexing: skip it; it's re-indexed next time.
            val input = openOrNull(prepared) ?: return false
            input.use {
                awaitInFlight(window - 1)
                lock.withLock { inFlight[offerId] = prepared }
                val sha256 = stream(offerId, prepared.item, offset, it)
                channel.send(FileEnd(offerId, sha256))
            }
            return true
        }

        /** The bytes sent: the original, or the original with its details removed. */
        private fun openOrNull(prepared: Prepared): BufferedSource? = try {
            val original = source.open(prepared.item)
            when (val redaction = prepared.redaction) {
                is Redaction.Edited -> redaction.open(original.buffer().inputStream()).source().buffer()
                else -> original.buffer()
            }
        } catch (_: IOException) {
            null
        }

        /** Sends `FILE_BEGIN` and the bytes after [offset]; returns the SHA-256 of the whole file. */
        private fun stream(offerId: Int, item: SourceItem, offset: Long, input: BufferedSource): ByteString {
            val digest = MessageDigest.getInstance("SHA-256")
            hashPrefix(input, digest, offset)
            channel.send(FileBegin(offerId, offset))
            var n = input.read(buffer, 0, buffer.size)
            while (n != -1) {
                digest.update(buffer, 0, n)
                channel.sendData(buffer, n)
                count {
                    bytesSent += n
                    bytesDone += n
                }
                report(item)
                n = input.read(buffer, 0, buffer.size)
            }
            return digest.digest().toByteString()
        }

        /** The consumer already holds the first [offset] bytes; hash them without sending. */
        private fun hashPrefix(input: BufferedSource, digest: MessageDigest, offset: Long) {
            var remaining = offset
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n == -1) break // The file shrank; the consumer's size check will reject it.
                digest.update(buffer, 0, n)
                remaining -= n
            }
            count { bytesDone += offset }
        }

        /** The replies' thread: confirmations update the session; offer responses go to the sending thread. */
        @Suppress("TooGenericExceptionCaught") // Anything that ends this thread must end the session too.
        private fun readReplies() {
            try {
                while (true) {
                    when (val message = channel.receive()) {
                        is FileAck -> onAck(message)
                        is OfferResponse -> responses.put(message)
                        is Bye -> throw PeerEndedSessionException(message.reason)
                        else -> throw ProtocolException("Unexpected ${message::class.simpleName}")
                    }
                }
            } catch (e: Exception) {
                if (finished) return // The consumer closing after our Bye.
                failure = e as? IOException ?: ProtocolException("Reading replies failed", e)
                responses.put(failure!!)
                lock.withLock { changed.signalAll() }
                connection.close() // Unblocks the sending thread if it's writing.
            }
        }

        private fun onAck(ack: FileAck) {
            val prepared = lock.withLock { inFlight[ack.offerId] }
                ?: throw ProtocolException("ACK for offer ${ack.offerId}, which isn't in flight")
            val confirmed = ack.status == FileAck.Status.OK
            if (confirmed) {
                ledger.markSent(consumer, prepared.descriptor.fingerprint, ack.contentId)
                source.confirmed(prepared.item)
            }
            // In flight until recorded and counted: the session, waiting for nothing to be in flight, then counts it.
            lock.withLock {
                inFlight.remove(ack.offerId) ?: throw ProtocolException("ACK for offer ${ack.offerId} twice")
                if (confirmed) sent++ else failed++
                changed.signalAll()
            }
            completeItem(prepared.item)
        }

        private fun awaitResponse(): OfferResponse = when (val next = responses.take()) {
            is OfferResponse -> next
            is IOException -> throw next
            else -> throw ProtocolException("Unexpected $next")
        }

        /** Waits until at most [count] files await confirmation. */
        private fun awaitInFlight(count: Int) = lock.withLock {
            while (inFlight.size > count && failure == null) changed.await()
            failure?.let { throw it }
        }

        private inline fun count(update: () -> Unit) = lock.withLock { update() }

        private fun completeItem(item: SourceItem) {
            count { itemsDone++ }
            report(item)
        }

        private fun report(item: SourceItem) {
            val snapshot = lock.withLock {
                TransferProgress(itemsDone, itemsTotal, bytesDone, bytesTotal, item.descriptor.displayName)
            }
            progress.onProgress(snapshot)
        }
    }

    /** An item as it's offered: with the size and gallery details of what is actually sent. */
    private class Prepared(val item: SourceItem, val descriptor: MediaDescriptor, val redaction: Redaction)

    private companion object {
        const val WINDOW = 16
    }
}

internal fun MediaDescriptor.toOfferItem(offerId: Int) = OfferItem(
    offerId = offerId,
    size = size,
    fingerprint = fingerprint,
    mimeType = mimeType,
    displayName = displayName,
    capturedAtMillis = meta.capturedAtMillis,
    utcOffsetMinutes = meta.utcOffsetMinutes,
    album = meta.album,
    favorite = meta.favorite,
    modifiedAtMillis = meta.modifiedAtMillis,
)

internal fun OfferItem.toDescriptor() = MediaDescriptor(
    fingerprint = fingerprint,
    size = size,
    mimeType = mimeType,
    displayName = displayName,
    meta = MediaMeta(capturedAtMillis, utcOffsetMinutes, album, favorite, modifiedAtMillis),
)

/**
 * The items one sync sends, shared by its connections: each takes the next batch when it's ready for more, so no
 * item goes twice and a faster connection simply takes more.
 */
class WorkQueue(items: List<SourceItem>) {
    private val queue = ConcurrentLinkedQueue(items)
    val total: Int = items.size
    val totalBytes: Long = items.sumOf { it.descriptor.size }

    /** Up to [maxItems], ending once they reach [maxBytes]; always at least one while there is one. */
    fun take(maxItems: Int, maxBytes: Long = Long.MAX_VALUE): List<SourceItem> = buildList {
        var bytes = 0L
        while (size < maxItems && bytes < maxBytes) {
            val item = queue.poll() ?: break
            add(item)
            bytes += item.descriptor.size
        }
    }

    fun isEmpty(): Boolean = queue.isEmpty()
}

/** Sorts what's waiting in this order, apart from shared items; within photos or videos, the newest first. */
internal fun SendingOrder.comparator(): Comparator<SourceItem> {
    val newestFirst = compareByDescending<SourceItem> { it.takenAtMillis() }
    return when (resolved(overInternet = false)) {
        SendingOrder.OLDEST_FIRST -> compareBy { it.takenAtMillis() }
        SendingOrder.PHOTOS_FIRST -> compareBy<SourceItem> { it.isVideo() }.then(newestFirst)
        SendingOrder.VIDEOS_FIRST -> compareBy<SourceItem> { !it.isVideo() }.then(newestFirst)
        else -> newestFirst
    }
}

/** When it was taken, or else when its file last changed (screenshots, saved images). */
private fun SourceItem.takenAtMillis(): Long =
    descriptor.meta.capturedAtMillis ?: descriptor.meta.modifiedAtMillis ?: 0L

private fun SourceItem.isVideo(): Boolean = descriptor.mimeType.startsWith("video/")
