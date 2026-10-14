/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import java.io.Closeable
import okio.ByteString
import okio.Source

// Interfaces the engine needs from the platform. Android implements them with MediaStore and Room;
// tests use the in-memory fakes in testFixtures. All calls are blocking and made on an I/O thread.

/** The producer's photos and videos that are eligible for sync. */
interface MediaSource {
    fun items(): List<SourceItem>

    /** Opens the original bytes (location metadata included). */
    fun open(item: SourceItem): Source

    /** Opens the original bytes for reading anywhere, to find their metadata before sending (docs/PLAN.md §6.4). */
    fun openRanges(item: SourceItem): RangeReader
}

/** Reads parts of one file; close it when done. */
interface RangeReader :
    ByteRangeReader,
    Closeable

/** Producer side: what each consumer has confirmed (docs/PLAN.md §5.6). */
interface SentLedger {
    fun sentFingerprints(consumer: DeviceId): Set<ByteString>

    fun markSent(consumer: DeviceId, fingerprint: ByteString, contentId: ByteString)
}

/** Consumer side: everything ever received, kept after the files themselves are gone (docs/PLAN.md §5.6). */
interface ReceivedLedger {
    /** The content id of an item already received with this fingerprint, from any producer. */
    fun contentIdOf(fingerprint: ByteString): ByteString?

    fun record(item: ReceivedItem)
}

/** Consumer side: where received bytes are written. */
interface MediaSink {
    /** Bytes that can still be accepted before the storage guard stops receiving. */
    fun availableBytes(): Long

    /** Bytes kept from an interrupted transfer of this item from this producer, or 0. */
    fun partialSize(producer: DeviceId, fingerprint: ByteString): Long

    /** Opens the destination for [descriptor]. With [offset] > 0, continues the kept partial transfer. */
    fun open(producer: DeviceId, descriptor: MediaDescriptor, offset: Long): IncomingFile
}

/**
 * One file being received. [close] without [commit] keeps what was written so a later session can
 * resume; [discard] removes it.
 */
interface IncomingFile : Closeable {
    /** The bytes already stored before this session, so the full-file hash can be completed. */
    fun existingBytes(): Source

    fun write(bytes: ByteArray, offset: Int, byteCount: Int)

    /** Makes the complete, verified file visible (e.g. clears MediaStore's IS_PENDING) and returns its location. */
    fun commit(): String

    fun discard()
}

data class PairedPeer(val id: DeviceId, val name: String, val role: DeviceRole)

/** Devices this device trusts. */
interface PeerRegistry {
    fun isPaired(id: DeviceId): Boolean

    /** Also called when a paired device pairs again; that updates it and keeps its ledgers. */
    fun register(peer: PairedPeer)
}

data class PairingRequest(val peerId: DeviceId, val peerName: String, val token: ByteString)

enum class PairingDecision { ACCEPTED, DECLINED, INVALID_TOKEN, TIMED_OUT }

/**
 * Consumer side: checks the one-time token from the QR code and asks the user to confirm.
 * May block while the user decides.
 */
fun interface PairingGate {
    fun decide(request: PairingRequest): PairingDecision
}

/**
 * How this device introduces itself in `Hello`.
 *
 * @property schedule consumer only: the sync schedule its producers follow (docs/PLAN.md §10.1).
 */
data class LocalDevice(val name: String, val appVersion: String, val schedule: SyncSchedule? = null)
