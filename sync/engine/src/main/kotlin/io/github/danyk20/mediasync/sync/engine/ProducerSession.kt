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
)

/**
 * Sends everything the consumer hasn't confirmed yet, newest first (docs/PLAN.md §5), with the [details] the user
 * chose to send (docs/PLAN.md §6.3): removed details never leave this phone.
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
) {

    fun sync(connection: PeerConnection): SyncResult {
        val channel = FrameChannel(connection)
        val consumer = connection.peerId
        channel.send(Hello(PROTOCOL_VERSION, local.name, WireRole.PRODUCER, local.appVersion))
        val hello = channel.expect<Hello>()
        if (hello.protocolVersion != PROTOCOL_VERSION || hello.role != WireRole.CONSUMER) {
            channel.send(Bye(Bye.Reason.INCOMPATIBLE_VERSION))
            throw IncompatiblePeerException("Peer speaks v${hello.protocolVersion} as role ${hello.role}")
        }

        val pending = pendingFor(consumer)
        val run = Run(channel, consumer, itemsTotal = pending.size, bytesTotal = pending.sumOf { it.descriptor.size })
        run.consumerSchedule = SyncSchedule.decode(hello.schedule)
        for (batch in pending.chunked(OFFER_BATCH_SIZE)) {
            if (!shouldContinue()) run.cancelled = true
            if (run.cancelled) break
            run.offerBatch(batch)
        }
        channel.send(Bye(if (run.cancelled) Bye.Reason.CANCELLED else Bye.Reason.DONE))
        return run.result()
    }

    /** Items the consumer hasn't confirmed, without duplicates, newest first. */
    private fun pendingFor(consumer: DeviceId): List<SourceItem> {
        val confirmed = ledger.sentFingerprints(consumer)
        return source.items()
            .filter { it.descriptor.fingerprint !in confirmed }
            .distinctBy { it.descriptor.fingerprint }
            .sortedByDescending { it.descriptor.meta.capturedAtMillis ?: it.descriptor.meta.modifiedAtMillis ?: 0L }
    }

    private inner class Run(
        private val channel: FrameChannel,
        private val consumer: DeviceId,
        private val itemsTotal: Int,
        private val bytesTotal: Long,
    ) {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var nextOfferId = 0
        private var itemsDone = 0
        private var bytesDone = 0L
        private var sent = 0
        private var alreadyOnConsumer = 0
        private var failed = 0
        private var deferred = 0
        private var withheld = 0
        private var bytesSent = 0L
        var cancelled = false

        fun offerBatch(batch: List<SourceItem>) {
            val offered = batch.mapNotNull(::prepare).associateBy { nextOfferId++ }
            if (offered.isEmpty()) return
            channel.send(Offer(offered.map { (id, prepared) -> prepared.descriptor.toOfferItem(id) }))
            val response = channel.expect<OfferResponse>()

            for (have in response.have) {
                val prepared =
                    offered[have.offerId] ?: throw ProtocolException("HAVE for unknown offer ${have.offerId}")
                ledger.markSent(consumer, prepared.descriptor.fingerprint, have.contentId)
                alreadyOnConsumer++
                completeItem(prepared.item)
            }
            for (want in response.want) {
                if (!shouldContinue()) {
                    cancelled = true
                    return
                }
                val prepared =
                    offered[want.offerId] ?: throw ProtocolException("WANT for unknown offer ${want.offerId}")
                if (sendFile(want.offerId, prepared, want.resumeOffset)) sent++ else failed++
                completeItem(prepared.item)
            }
            deferred += offered.size - response.want.size - response.have.size
        }

        /** How [item] is sent under the chosen details; `null` if it isn't, now. */
        private fun prepare(item: SourceItem): Prepared? {
            if (details == PhotoDetails.KEEP_ALL) return Prepared(item, item.descriptor, Redaction.None)
            val redaction = plan(item)
            if (redaction == null || redaction is Redaction.Withheld) {
                if (redaction == null) failed++ else withheld++ // Unreadable now: offered again next time.
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

        /** @return whether the consumer confirmed the file. */
        private fun sendFile(offerId: Int, prepared: Prepared, offset: Long): Boolean {
            if (offset !in 0..prepared.descriptor.size) throw ProtocolException("Resume offset $offset out of range")
            // Changed since it was offered: its details may have too. It's offered again next time.
            if (details != PhotoDetails.KEEP_ALL && plan(prepared.item) != prepared.redaction) return false
            // Deleted or unreadable since indexing: skip it; it's re-indexed next time.
            val input = openOrNull(prepared) ?: return false
            val sha256 = input.use { stream(offerId, prepared.item, offset, it) }
            channel.send(FileEnd(offerId, sha256))

            val ack = channel.expect<FileAck>()
            if (ack.offerId != offerId) throw ProtocolException("ACK for offer ${ack.offerId}, expected $offerId")
            val confirmed = ack.status == FileAck.Status.OK
            if (confirmed) ledger.markSent(consumer, prepared.descriptor.fingerprint, ack.contentId)
            return confirmed
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
                bytesSent += n
                bytesDone += n
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
            bytesDone += offset
        }

        private fun completeItem(item: SourceItem) {
            itemsDone++
            report(item)
        }

        private fun report(item: SourceItem) = progress.onProgress(
            TransferProgress(itemsDone, itemsTotal, bytesDone, bytesTotal, item.descriptor.displayName),
        )

        var consumerSchedule: SyncSchedule? = null

        fun result() =
            SyncResult(sent, alreadyOnConsumer, failed, deferred, bytesSent, cancelled, consumerSchedule, withheld)
    }

    /** An item as it's offered: with the size and gallery details of what is actually sent. */
    private class Prepared(val item: SourceItem, val descriptor: MediaDescriptor, val redaction: Redaction)
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
