/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.TurboReady
import io.github.danyk20.mediasync.sync.protocol.TurboRequest
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.github.danyk20.mediasync.transport.PeerConnection
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okio.ByteString

/**
 * A direct Wi-Fi link between the two phones (Turbo transfer, docs/PLAN.md §8.3): the Wi-Fi Direct network the
 * consumer created, and where it listens on it.
 */
data class DirectLink(val networkName: String, val passphrase: String, val host: String, val port: Int) {
    /** Never the passphrase: this ends up in logs. */
    override fun toString() = "DirectLink($networkName at $host:$port)"

    companion object {
        /**
         * A fresh network for one Turbo transfer. Android wants Wi-Fi Direct names to start with `DIRECT-` and two
         * more letters or digits; the passphrase is random, so only the phone it's sent to can join.
         */
        fun credentials(random: SecureRandom = SecureRandom()): Pair<String, String> {
            fun text(length: Int) = String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })
            return "DIRECT-${text(2)}$NAME_MARKER" to text(PASSPHRASE_LENGTH)
        }

        /**
         * The *Turbo receive* network for [epoch] (docs/PLAN.md §8.3): derived from the consumer's [key], which its
         * paired producers know, so they can join it when the two phones share no network to ask on. It changes with
         * every epoch, so the name isn't a lasting trace of the phone.
         */
        fun derived(key: ByteString, epoch: Long): Pair<String, String> {
            fun text(label: String, length: Int): String {
                val mac = Mac.getInstance(HMAC)
                mac.init(SecretKeySpec(key.toByteArray(), HMAC))
                val bytes = mac.doFinal("mediasync-turbo-v1:$label:$epoch".toByteArray(Charsets.UTF_8))
                return String(CharArray(length) { ALPHABET[(bytes[it].toInt() and BYTE_MASK) % ALPHABET.length] })
            }
            return "DIRECT-${text("prefix", 2)}$NAME_MARKER-${text("name", NAME_TAIL)}" to
                text("passphrase", PASSPHRASE_LENGTH)
        }

        /** The *Turbo receive* epoch at [millis]: a consumer's network keeps its name this long. */
        fun epochAt(millis: Long): Long = millis / EPOCH_MILLIS

        /** In MediaSync's network names, so it only ever removes its own. */
        const val NAME_MARKER = "-MediaSync"

        const val EPOCH_MILLIS = 15 * 60_000L

        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private const val PASSPHRASE_LENGTH = 24
        private const val NAME_TAIL = 6
        private const val HMAC = "HmacSHA256"
        private const val BYTE_MASK = 0xFF
    }
}

/** A consumer's *Turbo receive* key: 32 random bytes. */
const val TURBO_KEY_BYTES = 32

/** Why the consumer couldn't open a direct link. */
enum class TurboUnavailable {
    /** It has no Wi-Fi Direct. */
    UNSUPPORTED,

    /** Its user hasn't allowed MediaSync to use Wi-Fi Direct. */
    NOT_ALLOWED,

    /** Wi-Fi Direct is there, but the link couldn't be set up now. */
    FAILED,
}

/** The consumer's answer to a Turbo request. */
sealed interface TurboAnswer {
    data class Ready(val link: DirectLink) : TurboAnswer

    data class Unavailable(val reason: TurboUnavailable) : TurboAnswer
}

/** The consumer's side of Turbo: opens the direct link, or says why it can't. Called on a session's thread. */
fun interface TurboHost {
    fun open(): TurboAnswer
}

/** The producer's side: asks the consumer for a direct link, over a session on the network they share now. */
class TurboSetup(private val local: LocalDevice) {

    fun request(connection: PeerConnection): TurboAnswer {
        val channel = FrameChannel(connection)
        channel.send(Hello(PROTOCOL_VERSION, local.name, WireRole.PRODUCER, local.appVersion))
        val hello = channel.expect<Hello>()
        if (hello.protocolVersion != PROTOCOL_VERSION || hello.role != WireRole.CONSUMER) {
            channel.send(Bye(Bye.Reason.INCOMPATIBLE_VERSION))
            throw IncompatiblePeerException("Peer speaks v${hello.protocolVersion} as role ${hello.role}")
        }
        channel.send(TurboRequest())
        val ready = channel.expect<TurboReady>() // An unpaired phone is told so here.
        channel.send(Bye())
        return ready.toAnswer()
    }
}

internal fun TurboAnswer.toWire(): TurboReady = when (this) {
    is TurboAnswer.Ready -> TurboReady(TurboReady.Status.OK, link.networkName, link.passphrase, link.host, link.port)

    is TurboAnswer.Unavailable -> TurboReady(
        when (reason) {
            TurboUnavailable.UNSUPPORTED -> TurboReady.Status.UNSUPPORTED
            TurboUnavailable.NOT_ALLOWED -> TurboReady.Status.NOT_ALLOWED
            TurboUnavailable.FAILED -> TurboReady.Status.FAILED
        },
    )
}

internal fun TurboReady.toAnswer(): TurboAnswer = when (status) {
    TurboReady.Status.OK -> TurboAnswer.Ready(DirectLink(networkName, passphrase, host, port))
    TurboReady.Status.UNSUPPORTED -> TurboAnswer.Unavailable(TurboUnavailable.UNSUPPORTED)
    TurboReady.Status.NOT_ALLOWED -> TurboAnswer.Unavailable(TurboUnavailable.NOT_ALLOWED)
    else -> TurboAnswer.Unavailable(TurboUnavailable.FAILED) // Including reasons a later version adds.
}
