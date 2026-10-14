/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.danyk20.mediasync.sync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import okio.ByteString

/** Bumped only for incompatible changes. New fields and message types don't need a bump (docs/PLAN.md §5.1). */
const val PROTOCOL_VERSION: Int = 1

/** A control message: everything on the wire except raw `DATA` frames. Encoded as protobuf. */
sealed interface ControlMessage

/** Wire codes for a device's role. Kept as plain integers so unknown future values don't break decoding. */
object WireRole {
    const val PRODUCER = 1
    const val CONSUMER = 2
}

/** First message in each direction on every connection. */
@Serializable
data class Hello(
    @ProtoNumber(1) val protocolVersion: Int,
    @ProtoNumber(2) val deviceName: String,
    @ProtoNumber(3) val role: Int,
    @ProtoNumber(4) val appVersion: String = "",
    /** Consumer only: bytes it can still accept. */
    @ProtoNumber(5) val freeSpaceBytes: Long = 0,
    /** Consumer only: its sync schedule (`SyncSchedule.encode`), which its producers follow; empty if none. */
    @ProtoNumber(6) val schedule: String = "",
) : ControlMessage

/** Producer → consumer while pairing: proves it scanned this consumer's QR code. */
@Serializable
data class PairRequest(@ProtoNumber(1) @Serializable(with = ByteStringSerializer::class) val token: ByteString) :
    ControlMessage

/** Consumer → producer: the outcome of a [PairRequest]. */
@Serializable
data class PairResult(@ProtoNumber(1) val accepted: Boolean, @ProtoNumber(2) val reason: Int = Reason.NONE) :
    ControlMessage {
    object Reason {
        const val NONE = 0
        const val DECLINED = 1
        const val INVALID_TOKEN = 2
        const val TIMED_OUT = 3
    }
}

/** Producer → consumer: a batch of items the producer would like to send. */
@Serializable
data class Offer(@ProtoNumber(1) val items: List<OfferItem>) : ControlMessage

/**
 * One offered photo or video.
 *
 * Gallery details live outside the file (`MediaStore`), so they travel here as a sidecar (PLAN.md §6.1).
 */
@Serializable
data class OfferItem(
    @ProtoNumber(1) val offerId: Int,
    @ProtoNumber(2) val size: Long,
    @ProtoNumber(3) @Serializable(with = ByteStringSerializer::class) val fingerprint: ByteString,
    @ProtoNumber(4) val mimeType: String,
    @ProtoNumber(5) val displayName: String,
    @ProtoNumber(6) val capturedAtMillis: Long? = null,
    @ProtoNumber(7) val utcOffsetMinutes: Int? = null,
    @ProtoNumber(8) val album: String? = null,
    @ProtoNumber(9) val favorite: Boolean = false,
    @ProtoNumber(10) val modifiedAtMillis: Long? = null,
)

/**
 * Consumer → producer: which offered items to send ([want]) and which it already has ([have]).
 * Items in neither list are deferred, typically because the consumer is low on space.
 */
@Serializable
data class OfferResponse(
    @ProtoNumber(1) val want: List<Want> = emptyList(),
    @ProtoNumber(2) val have: List<Have> = emptyList(),
) : ControlMessage

@Serializable
data class Want(
    @ProtoNumber(1) val offerId: Int,
    /** Bytes already received from an interrupted earlier transfer; sending resumes here. */
    @ProtoNumber(2) val resumeOffset: Long = 0,
)

@Serializable
data class Have(
    @ProtoNumber(1) val offerId: Int,
    @ProtoNumber(2) @Serializable(with = ByteStringSerializer::class) val contentId: ByteString,
)

/** Producer → consumer: `DATA` frames for [offerId], starting at [offset], follow until [FileEnd]. */
@Serializable
data class FileBegin(@ProtoNumber(1) val offerId: Int, @ProtoNumber(2) val offset: Long = 0) : ControlMessage

/** Producer → consumer: the item is complete. [sha256] covers the whole file, including resumed bytes. */
@Serializable
data class FileEnd(
    @ProtoNumber(1) val offerId: Int,
    @ProtoNumber(2) @Serializable(with = ByteStringSerializer::class) val sha256: ByteString,
) : ControlMessage

/** Consumer → producer: the outcome for one item. */
@Serializable
data class FileAck(
    @ProtoNumber(1) val offerId: Int,
    @ProtoNumber(2) val status: Int,
    @ProtoNumber(3) @Serializable(with = ByteStringSerializer::class) val contentId: ByteString = ByteString.EMPTY,
) : ControlMessage {
    object Status {
        /** Stored and verified; [contentId] is the SHA-256 of the stored bytes. */
        const val OK = 1

        /** Hash or size didn't match; nothing was kept. The producer may retry later. */
        const val CORRUPT = 2

        /** The consumer couldn't store the file (for example, storage full or write error). */
        const val FAILED = 3
    }
}

/** Either side: the session is over. */
@Serializable
data class Bye(@ProtoNumber(1) val reason: Int = Reason.DONE) : ControlMessage {
    object Reason {
        const val DONE = 0
        const val NOT_PAIRED = 1
        const val INCOMPATIBLE_VERSION = 2
        const val PROTOCOL_ERROR = 3
        const val CANCELLED = 4
    }
}
