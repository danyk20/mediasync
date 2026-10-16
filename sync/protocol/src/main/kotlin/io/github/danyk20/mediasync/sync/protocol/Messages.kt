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
    /**
     * Consumer only, and only to a paired producer: the key its *Turbo receive* links derive from (docs/PLAN.md
     * §8.3), so the producer can join one when the two share no network. Empty otherwise.
     */
    @ProtoNumber(7) @Serializable(with = ByteStringSerializer::class) val turboKey: ByteString = ByteString.EMPTY,
    /**
     * Consumer only, and only to that paired producer: the secret their internet signaling is sealed with (ADR 0009
     * §4), the same in every session. Empty otherwise.
     */
    @ProtoNumber(8) @Serializable(with = ByteStringSerializer::class) val pairSecret: ByteString = ByteString.EMPTY,
    /**
     * Consumer only, and only to a paired producer: the address of its rendezvous Worker, through which the two sync
     * over the internet (PLAN §8.4); empty when it has none.
     */
    @ProtoNumber(9) val rendezvousUrl: String = "",
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

/**
 * Producer → consumer: measures the link (docs/PLAN.md §7). The consumer answers with the same message when it's
 * ready; `DATA` frames of filler then follow until [LinkTestEnd].
 */
@Serializable
data class LinkTestBegin(@ProtoNumber(1) val reserved: Int = 0) : ControlMessage

/** Producer → consumer: the filler is over; [bytes] were sent. */
@Serializable
data class LinkTestEnd(@ProtoNumber(1) val bytes: Long) : ControlMessage

/** Consumer → producer: the filler that arrived, and how long from its first byte to its last. */
@Serializable
data class LinkTestResult(@ProtoNumber(1) val bytes: Long, @ProtoNumber(2) val micros: Long) : ControlMessage

/**
 * Producer → consumer: asks for Turbo transfer (docs/PLAN.md §8.3), a direct Wi-Fi link between the two phones. The
 * consumer answers with [TurboReady]. [aware]: the producer can join a Wi-Fi Aware link now; consumers before M6
 * ignore it and answer with Wi-Fi Direct.
 */
@Serializable
data class TurboRequest(@ProtoNumber(1) val reserved: Int = 0, @ProtoNumber(2) val aware: Boolean = false) :
    ControlMessage

/**
 * Consumer → producer: the direct link to join, when [status] is [Status.OK]: its Wi-Fi Direct network or Wi-Fi Aware
 * service ([kind]) by [networkName], its [passphrase], fresh for each Turbo transfer, and where this phone listens on
 * it. They travel inside the encrypted session, so only the paired phone learns them.
 */
@Serializable
data class TurboReady(
    @ProtoNumber(1) val status: Int,
    @ProtoNumber(2) val networkName: String = "",
    @ProtoNumber(3) val passphrase: String = "",
    @ProtoNumber(4) val host: String = "",
    @ProtoNumber(5) val port: Int = 0,
    @ProtoNumber(6) val kind: Int = Kind.WIFI_DIRECT,
) : ControlMessage {
    object Kind {
        const val WIFI_DIRECT = 0

        /** Only to a producer that asked with [TurboRequest.aware]; [host] is empty, as the link tells it. */
        const val WIFI_AWARE = 1
    }

    object Status {
        const val OK = 1

        /** This phone has no Wi-Fi Direct. */
        const val UNSUPPORTED = 2

        /** Its user hasn't allowed MediaSync to use Wi-Fi Direct (nearby devices, or location before Android 13). */
        const val NOT_ALLOWED = 3

        /** Wi-Fi Direct is there, but the link couldn't be set up now (Wi-Fi off, or busy). */
        const val FAILED = 4
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
