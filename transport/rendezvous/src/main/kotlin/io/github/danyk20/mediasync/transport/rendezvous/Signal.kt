/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the two phones tell each other through the mailbox to set up a WebRTC connection (PLAN §8.4), each sealed with
 * their pair key. [session] tells attempts apart, so a late message from an earlier one is ignored.
 */
@Serializable
sealed interface Signal {
    val session: String

    /** From the producer, which starts: its session description. */
    @Serializable
    @SerialName("offer")
    data class Offer(override val session: String, val sdp: String) : Signal

    @Serializable
    @SerialName("answer")
    data class Answer(override val session: String, val sdp: String) : Signal

    /** A way to reach the sender, found after its description (trickle ICE). */
    @Serializable
    @SerialName("candidate")
    data class Candidate(
        override val session: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : Signal

    /** The sender gave up on, or ended, this attempt. */
    @Serializable
    @SerialName("bye")
    data class Bye(override val session: String) : Signal
}

/** Seals and opens [Signal]s with the pair's [cipher]. */
class SignalCodec(private val cipher: SignalingCipher) {
    fun seal(signal: Signal): ByteArray =
        cipher.seal(JSON.encodeToString(Signal.serializer(), signal).encodeToByteArray())

    /** @throws IOException if it wasn't sealed for this pair, or isn't a signal this version knows. */
    fun open(sealed: ByteArray): Signal = try {
        JSON.decodeFromString(Signal.serializer(), cipher.open(sealed).decodeToString())
    } catch (e: SerializationException) {
        throw IOException("Not a signal this version knows", e)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
