/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.ControlMessage
import io.github.danyk20.mediasync.sync.protocol.FileAck
import io.github.danyk20.mediasync.sync.protocol.FileBegin
import io.github.danyk20.mediasync.sync.protocol.FileEnd
import io.github.danyk20.mediasync.sync.protocol.FrameCodec
import io.github.danyk20.mediasync.sync.protocol.FrameType
import io.github.danyk20.mediasync.sync.protocol.Have
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.Offer
import io.github.danyk20.mediasync.sync.protocol.OfferResponse
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.PairRequest
import io.github.danyk20.mediasync.sync.protocol.PairResult
import io.github.danyk20.mediasync.sync.protocol.ProtocolException
import io.github.danyk20.mediasync.sync.protocol.Want
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import java.io.IOException
import java.security.MessageDigest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.buffer

/** The outcome of serving one incoming connection. */
sealed interface ServeResult {
    data class Synced(val received: Int, val alreadyHad: Int, val failed: Int, val bytesReceived: Long) : ServeResult

    data class Paired(val peer: PairedPeer) : ServeResult

    data class PairingRefused(val decision: PairingDecision) : ServeResult

    /** The peer wasn't allowed to sync, or speaks an incompatible protocol. */
    data class Rejected(val byeReason: Int) : ServeResult
}

/**
 * Serves one producer connection (docs/PLAN.md §5). A producer that asks to pair goes through pairing,
 * even if it's already paired (its QR code was scanned again); a paired producer otherwise sends.
 *
 * Every file is hashed while it's written and only becomes visible after its SHA-256 matches. A dropped
 * connection keeps the partial file so the next session resumes where this one stopped.
 */
class ConsumerSession(
    private val local: LocalDevice,
    private val registry: PeerRegistry,
    private val ledger: ReceivedLedger,
    private val sink: MediaSink,
    private val pairingGate: PairingGate,
    private val progress: ProgressListener = ProgressListener {},
) {

    fun serve(connection: PeerConnection): ServeResult {
        val channel = FrameChannel(connection)
        val hello = channel.expect<Hello>()
        channel.send(
            Hello(
                PROTOCOL_VERSION,
                local.name,
                WireRole.CONSUMER,
                local.appVersion,
                sink.availableBytes(),
                local.schedule?.encode().orEmpty(),
            ),
        )
        if (hello.protocolVersion != PROTOCOL_VERSION || hello.role != WireRole.PRODUCER) {
            channel.send(Bye(Bye.Reason.INCOMPATIBLE_VERSION))
            return ServeResult.Rejected(Bye.Reason.INCOMPATIBLE_VERSION)
        }
        val producer = connection.peerId
        val first = channel.receive()
        return when {
            first is PairRequest -> pair(channel, producer, hello.deviceName, first)

            registry.isPaired(producer) -> Receiver(channel, producer).run(first)

            else -> {
                channel.send(Bye(Bye.Reason.NOT_PAIRED))
                ServeResult.Rejected(Bye.Reason.NOT_PAIRED)
            }
        }
    }

    /** Re-pairing a known producer only updates its name: what it sent before stays recorded. */
    private fun pair(
        channel: FrameChannel,
        producer: DeviceId,
        producerName: String,
        request: PairRequest,
    ): ServeResult {
        val decision = pairingGate.decide(PairingRequest(producer, producerName, request.token))
        val result = if (decision == PairingDecision.ACCEPTED) {
            val peer = PairedPeer(producer, producerName, DeviceRole.PRODUCER)
            registry.register(peer)
            channel.send(PairResult(accepted = true))
            ServeResult.Paired(peer)
        } else {
            channel.send(PairResult(accepted = false, reason = decision.toWireReason()))
            ServeResult.PairingRefused(decision)
        }
        awaitEnd(channel)
        return result
    }

    private fun awaitEnd(channel: FrameChannel) {
        try {
            while (channel.receive() !is Bye) {
                // Ignore anything else until the producer says goodbye.
            }
        } catch (_: PeerEndedSessionException) {
            // Closing without Bye is fine at this point.
        }
    }

    private inner class Receiver(private val channel: FrameChannel, private val producer: DeviceId) {
        private val offered = HashMap<Int, MediaDescriptor>()
        private val wantedThisSession = HashSet<ByteString>()
        private val buffer = ByteArray(FrameCodec.MAX_PAYLOAD_SIZE)
        private var itemsTotal = 0
        private var itemsDone = 0
        private var bytesTotal = 0L
        private var bytesDone = 0L
        private var received = 0
        private var alreadyHad = 0
        private var failed = 0

        /** Receives until `Bye`, starting with [first], the message already read after `Hello`. */
        fun run(first: ControlMessage): ServeResult {
            var message = first
            while (message !is Bye) {
                when (message) {
                    is Offer -> answer(message)
                    is FileBegin -> receive(message)
                    else -> throw ProtocolException("Unexpected ${message::class.simpleName} while syncing")
                }
                message = channel.receive()
            }
            return ServeResult.Synced(received, alreadyHad, failed, bytesDone)
        }

        private fun answer(offer: Offer) {
            var budget = sink.availableBytes()
            val want = mutableListOf<Want>()
            val have = mutableListOf<Have>()
            for (item in offer.items) {
                val descriptor = item.toDescriptor()
                offered[item.offerId] = descriptor
                val existing = ledger.contentIdOf(descriptor.fingerprint)
                when {
                    existing != null -> {
                        have += Have(item.offerId, existing)
                        alreadyHad++
                    }

                    descriptor.fingerprint in wantedThisSession -> Unit

                    // Duplicate within one session: defer.

                    else -> {
                        val resumeAt = sink.partialSize(producer, descriptor.fingerprint).coerceIn(0, descriptor.size)
                        val needed = descriptor.size - resumeAt
                        if (needed <= budget) {
                            want += Want(item.offerId, resumeAt)
                            wantedThisSession += descriptor.fingerprint
                            budget -= needed
                            itemsTotal++
                            bytesTotal += descriptor.size
                        }
                    }
                }
            }
            channel.send(OfferResponse(want, have))
        }

        private fun receive(begin: FileBegin) {
            val transfer = Transfer(begin.offerId, validated(begin), begin.offset)
            val end = try {
                readUntilEnd(transfer)
            } catch (e: IOException) {
                transfer.keepPartial()
                throw e
            }
            transfer.finish(end)
        }

        /** The offered item [begin] refers to; it may only resume from bytes this device actually kept. */
        private fun validated(begin: FileBegin): MediaDescriptor {
            val descriptor = offered[begin.offerId] ?: throw ProtocolException("FILE_BEGIN for unknown offer")
            val kept = sink.partialSize(producer, descriptor.fingerprint)
            if (begin.offset !in 0..minOf(kept, descriptor.size)) {
                throw ProtocolException("FILE_BEGIN offset ${begin.offset} doesn't match the $kept bytes kept")
            }
            return descriptor
        }

        private fun readUntilEnd(transfer: Transfer): FileEnd {
            while (true) {
                val header = channel.nextHeader()
                if (header.type != FrameType.DATA) {
                    return channel.readControl(header) as? FileEnd
                        ?: throw ProtocolException("Expected DATA or FILE_END while receiving")
                }
                transfer.append(channel.readData(header, buffer))
            }
        }

        /** One file being received: writes, hashes and finally commits or discards it. */
        private inner class Transfer(val offerId: Int, val descriptor: MediaDescriptor, offset: Long) {
            private val digest = MessageDigest.getInstance("SHA-256")
            private val file: IncomingFile? = try {
                sink.open(producer, descriptor, offset)
            } catch (_: IOException) {
                null // Can't store it; still read the data so the session can continue.
            }
            private var writable = file != null && hashExisting(file, offset)
            private var length = offset

            init {
                bytesDone += offset
            }

            /** Takes the [byteCount] bytes just read into [buffer]. */
            fun append(byteCount: Int) {
                length += byteCount
                bytesDone += byteCount
                if (length <= descriptor.size) {
                    digest.update(buffer, 0, byteCount)
                    if (writable) writable = writeOrFail(byteCount)
                }
                progress.onProgress(
                    TransferProgress(itemsDone, itemsTotal, bytesDone, bytesTotal, descriptor.displayName),
                )
            }

            fun finish(end: FileEnd) {
                if (end.offerId != offerId) throw ProtocolException("FILE_END for offer ${end.offerId}, not $offerId")
                itemsDone++
                val sha256 = digest.digest().toByteString()
                val status = when {
                    file == null || !writable -> FileAck.Status.FAILED
                    length != descriptor.size || sha256 != end.sha256 -> FileAck.Status.CORRUPT
                    else -> commit(file, sha256)
                }
                if (status == FileAck.Status.OK) received++ else failed++
                if (status != FileAck.Status.OK) file?.discard()
                file?.close()
                channel.send(FileAck(offerId, status, if (status == FileAck.Status.OK) sha256 else ByteString.EMPTY))
            }

            /** The connection broke: keep what was written so the next session can resume. */
            fun keepPartial() {
                file?.close()
            }

            private fun commit(file: IncomingFile, sha256: ByteString): Int = try {
                ledger.record(ReceivedItem(producer, descriptor, sha256, file.commit()))
                FileAck.Status.OK
            } catch (_: IOException) {
                FileAck.Status.FAILED
            }

            /** Re-hashes the bytes kept from an interrupted transfer; `false` if they can't be read back. */
            private fun hashExisting(file: IncomingFile, offset: Long): Boolean = try {
                file.existingBytes().buffer().use { existing -> digestPrefix(existing, offset) }
            } catch (_: IOException) {
                false
            }

            private fun digestPrefix(existing: okio.BufferedSource, offset: Long): Boolean {
                var remaining = offset
                while (remaining > 0) {
                    val n = existing.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n == -1) return false
                    digest.update(buffer, 0, n)
                    remaining -= n
                }
                return true
            }

            private fun writeOrFail(byteCount: Int): Boolean = try {
                file!!.write(buffer, 0, byteCount)
                true
            } catch (_: IOException) {
                false
            }
        }
    }
}

private fun PairingDecision.toWireReason(): Int = when (this) {
    PairingDecision.ACCEPTED -> PairResult.Reason.NONE
    PairingDecision.DECLINED -> PairResult.Reason.DECLINED
    PairingDecision.INVALID_TOKEN -> PairResult.Reason.INVALID_TOKEN
    PairingDecision.TIMED_OUT -> PairResult.Reason.TIMED_OUT
}

internal fun Int.toPairingDecision(): PairingDecision = when (this) {
    PairResult.Reason.INVALID_TOKEN -> PairingDecision.INVALID_TOKEN
    PairResult.Reason.TIMED_OUT -> PairingDecision.TIMED_OUT
    else -> PairingDecision.DECLINED
}
