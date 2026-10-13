/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingGate
import io.github.danyk20.mediasync.sync.engine.PairingLink
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The consumer's pairing invitation: one token, valid for a few minutes, usable by one phone
 * (docs/PLAN.md §11).
 */
class PairingWindow(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private var token: ByteString? = null
    private var expiresAtMillis = 0L

    /** Opens a new invitation, replacing any earlier one. */
    @Synchronized
    fun open(validForMillis: Long = DEFAULT_VALIDITY_MILLIS): ByteString {
        val bytes = ByteArray(PairingLink.TOKEN_BYTES).also(random::nextBytes).toByteString()
        token = bytes
        expiresAtMillis = clock() + validForMillis
        return bytes
    }

    @Synchronized
    fun close() {
        token = null
    }

    /** Whether unknown phones may currently connect to present a token. */
    val isOpen: Boolean
        @Synchronized get() = token != null && clock() < expiresAtMillis

    /** Checks [presented] and, if it's right, uses the invitation up: a QR code pairs one phone. */
    @Synchronized
    fun redeem(presented: ByteString): Boolean {
        val expected = token?.takeIf { clock() < expiresAtMillis } ?: return false
        val matches = MessageDigest.isEqual(expected.toByteArray(), presented.toByteArray())
        if (matches) token = null
        return matches
    }

    companion object {
        val DEFAULT_VALIDITY_MILLIS = TimeUnit.MINUTES.toMillis(5)
    }
}

/** A pairing request waiting for the consumer's user. */
class PendingApproval internal constructor(val producerName: String, private val answer: CompletableDeferred<Boolean>) {
    fun accept() = answer.complete(true)

    fun decline() = answer.complete(false)
}

/** Asks the consumer's user to approve each pairing request, and waits for the answer. */
class PairingApprovals(private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
    private val _pending = MutableStateFlow<PendingApproval?>(null)

    /** The request the UI should show, if any. */
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()

    /** Blocks the calling (I/O) thread until the user answers; `null` when nobody answered in time. */
    fun ask(producerName: String): Boolean? {
        val answer = CompletableDeferred<Boolean>()
        _pending.value = PendingApproval(producerName, answer)
        return try {
            runBlocking { withTimeoutOrNull(timeoutMillis) { answer.await() } }
        } finally {
            _pending.value = null
        }
    }

    companion object {
        val DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60)
    }
}

/** The consumer's [PairingGate]: a valid, unused token *and* the user's approval. */
internal fun pairingGate(window: PairingWindow, approvals: PairingApprovals) = PairingGate { request ->
    if (!window.redeem(request.token)) return@PairingGate PairingDecision.INVALID_TOKEN
    when (approvals.ask(request.peerName)) {
        true -> PairingDecision.ACCEPTED
        false -> PairingDecision.DECLINED
        null -> PairingDecision.TIMED_OUT
    }
}
