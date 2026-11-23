/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.util.Lock
import io.github.danyk20.mediasync.transport.PeerConnection
import io.github.danyk20.mediasync.transport.lan.apple.shim.ms_connection_receive
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_t
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_content_context_t
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_t
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

private const val MAX_RECEIVE_BYTES = 65_536
private const val MAX_SEND_BYTES = 1 shl 20

/**
 * Sends that may be queued in Network.framework at once. Each is at most [MAX_SEND_BYTES], so a writer never queues
 * more than four megabytes ahead of the network; the fifth write waits for the first send to complete.
 */
private const val MAX_SENDS_IN_FLIGHT = 4

/** How long [NwPeerConnection.close] waits for Network.framework to report the connection cancelled. */
private const val CANCEL_WAIT_MILLIS = 2_000L

private const val CLOSED_MESSAGE = "The connection is closed"

/**
 * A [PeerConnection] over a Network.framework connection, once it has been started.
 *
 * The okio streams block the calling thread, as the JVM sockets do. The rules:
 * - Reads are pull-based: one receive is in flight, and a chunk is requested only when the caller needs bytes, so
 *   nothing buffers without bound. End of stream is the peer's close (the stream then returns -1).
 * - Writes are bounded: at most [MAX_SENDS_IN_FLIGHT] sends are queued. [NwSink.flush] waits until every queued send
 *   has completed, so a send error reaches the caller there.
 * - Every read and write has a bound from [timeouts]. A read or write that runs out cancels the connection and throws
 *   an [IOException]. [close] is idempotent and may be called from any thread, including while a read is waiting.
 * - A failure is sticky: once the connection has failed or is closed, every later read and write throws.
 *
 * There is no half-close: as on the JVM, closing either stream closes the connection.
 */
internal class NwPeerConnection(
    private val connection: nw_connection_t,
    private val queue: dispatch_queue_t,
    private val verdict: PeerVerdict,
    private val timeouts: AppleLanTimeouts,
    override val remoteAddress: String,
) : PeerConnection {
    private val lock = Lock()
    private val context: nw_content_context_t =
        nw_content_context_create("mediasync") ?: throw AppleLanException("nw_content_context_create failed")
    private val window = dispatch_semaphore_create(MAX_SENDS_IN_FLIGHT.toLong())
        ?: throw AppleLanException("dispatch_semaphore_create failed")
    private val handshake = OneShot<Handshake>()
    private val cancelled = OneShot<Unit>()

    // Guarded by [lock].
    private var closed = false
    private var failure: String? = null
    private var reader: OneShot<Reply>? = null

    // Network.framework may call a completion after the connection is cancelled, so this object stays retained (see
    // CallbackRetention) until the cancelled state has arrived and no completion is outstanding.
    private var outstanding = 0
    private var cancelledSeen = false
    private var retained = false

    override val peerId: DeviceId get() = verdict.trusted ?: throw IOException("The peer was not verified")
    override val source: BufferedSource = NwSource().buffer()
    override val sink: BufferedSink = NwSink().buffer()

    /** Installs the state handler, starts the connection and waits up to [waitMillis] for its handshake. */
    fun start(waitMillis: Long): Handshake? {
        lock.withLock { retained = true }
        CallbackRetention.retain(this)
        nw_connection_set_queue(connection, queue)
        nw_connection_set_state_changed_handler(connection) { state, error ->
            guardCallback({ fail("The state callback failed: ${it.message}") }) { onState(state, error) }
        }
        nw_connection_start(connection)
        return handshake.await(waitMillis)
    }

    /** Ends the connection. Idempotent, and waits (bounded) until Network.framework has cancelled it. */
    override fun close() = shutdown(CLOSED_MESSAGE, awaitCancel = true)

    /** Runs on the connection's queue. */
    private fun onState(state: nw_connection_state_t, error: nw_error_t) {
        when (state) {
            nw_connection_state_ready -> handshake.offer(Handshake.Ready)

            nw_connection_state_waiting, nw_connection_state_failed -> {
                val waiting = state == nw_connection_state_waiting
                val code = error?.let { nw_error_get_error_code(it) }
                // Before the handshake this is its outcome; after it, the connection has lost its path or failed.
                if (!handshake.offer(Handshake.Failed(waiting, code))) {
                    fail("The connection ${if (waiting) "is waiting" else "failed"}, error ${code ?: "unknown"}")
                    // Without this the callback retention keeps the object until close().
                    nw_connection_cancel(connection)
                }
            }

            nw_connection_state_cancelled -> {
                handshake.offer(Handshake.Failed(waiting = false, code = null))
                fail(CLOSED_MESSAGE)
                lock.withLock { cancelledSeen = true }
                cancelled.offer(Unit)
                releaseIfIdle()
            }

            else -> Unit
        }
    }

    /** Ends a counted completion, after its body has run. Called from the completions, on the connection's queue. */
    internal fun endCallback() {
        lock.withLock { outstanding-- }
        releaseIfIdle()
    }

    /** Lets go of this object once it is cancelled and no completion is outstanding. Only once. */
    private fun releaseIfIdle() {
        val release = lock.withLock {
            val idle = retained && cancelledSeen && outstanding == 0
            if (idle) retained = false
            idle
        }
        if (release) CallbackRetention.release(this)
    }

    /** Records [reason] as the connection's failure (the first one wins) and wakes a reader that is waiting. */
    private fun fail(reason: String) {
        val waiter = lock.withLock {
            if (failure == null) failure = reason
            reader
        }
        waiter?.offer(Reply.Failed(reason))
    }

    /**
     * Ends the connection, once. Records [reason] unless a failure came first, wakes a waiting reader and cancels the
     * connection. [awaitCancel] waits (bounded) for the cancelled state; it must not be used on the connection's queue.
     */
    private fun shutdown(reason: String, awaitCancel: Boolean) {
        val first = lock.withLock {
            if (closed) {
                false
            } else {
                closed = true
                if (failure == null) failure = reason
                true
            }
        }
        if (!first) return
        val waiter = lock.withLock { reader }
        waiter?.offer(Reply.Failed(reason))
        nw_connection_cancel(connection)
        if (awaitCancel) cancelled.await(CANCEL_WAIT_MILLIS)
    }

    /** Ends the connection because [what] ran out of time, and throws the [IOException] for it. */
    private fun timedOut(what: String, millis: Long): Nothing {
        val message = "$what timed out after $millis ms"
        shutdown(message, awaitCancel = false)
        throw IOException(message)
    }

    private fun checkNotFailed() {
        val reason = lock.withLock { failure }
        if (reason != null) throw IOException(reason)
    }

    /** Waits for the next chunk. At end of stream the chunk is empty and [Reply.Data.complete] is set. */
    private fun receive(): Reply.Data {
        val waiter = OneShot<Reply>()
        lock.withLock {
            failure?.let { throw IOException(it) }
            reader = waiter
            outstanding++
        }
        // The completion takes the pending receive back out of this reference, exactly once per receive.
        val reference = StableRef.create(PendingReceive(this, waiter)).asCPointer()
        ms_connection_receive(connection, 1u, MAX_RECEIVE_BYTES.toUInt(), reference, receiveCallback)
        val reply = waiter.await(timeouts.readMillis)
        lock.withLock { reader = null }
        return when (reply) {
            null -> timedOut("Read", timeouts.readMillis)
            is Reply.Failed -> throw IOException(reply.message)
            is Reply.Data -> reply
        }
    }

    /** Queues one send of at most [MAX_SEND_BYTES]. Blocks while [MAX_SENDS_IN_FLIGHT] sends are queued. */
    @Suppress("TooGenericExceptionCaught")
    private fun send(bytes: ByteArray) {
        if (dispatch_semaphore_wait(window, deadlineAfter(timeouts.writeMillis)) != 0L) {
            timedOut("Write", timeouts.writeMillis)
        }
        val reason = lock.withLock {
            val current = failure
            if (current == null) outstanding++
            current
        }
        if (reason != null) {
            dispatch_semaphore_signal(window)
            throw IOException(reason)
        }
        val data = bytes.toDispatchData(queue)
        nw_connection_send(connection, data, context, true) { error ->
            try {
                if (error != null) fail("Write failed with error ${nw_error_get_error_code(error)}")
            } catch (cause: Throwable) {
                fail("The send callback failed: ${cause.message}")
            } finally {
                dispatch_semaphore_signal(window)
                endCallback()
            }
        }
    }

    /** Waits until every queued send has completed, then reports a failure if one happened. */
    private fun drain() {
        checkNotFailed()
        val deadline = deadlineAfter(timeouts.writeMillis)
        var taken = 0
        try {
            while (taken < MAX_SENDS_IN_FLIGHT) {
                if (dispatch_semaphore_wait(window, deadline) != 0L) timedOut("Write", timeouts.writeMillis)
                taken++
            }
        } finally {
            // Give back what was taken, so the window ends with all its permits (a dispatch semaphore must not be
            // released below its initial count).
            repeat(taken) { dispatch_semaphore_signal(window) }
        }
        checkNotFailed()
    }

    private inner class NwSource : Source {
        private var pending = ByteArray(0)
        private var position = 0
        private var endOfStream = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0) { "byteCount < 0: $byteCount" }
            if (byteCount == 0L) return 0L
            while (position == pending.size) {
                if (endOfStream) return -1L
                val reply = receive()
                pending = reply.bytes
                position = 0
                if (reply.complete) endOfStream = true
            }
            val count = minOf(byteCount, (pending.size - position).toLong()).toInt()
            sink.write(pending, position, count)
            position += count
            return count.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = this@NwPeerConnection.close()
    }

    private inner class NwSink : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            checkNotFailed()
            var remaining = byteCount
            while (remaining > 0) {
                val count = minOf(remaining, MAX_SEND_BYTES.toLong()).toInt()
                send(source.readByteArray(count.toLong()))
                remaining -= count
            }
        }

        override fun flush() = drain()

        override fun timeout(): Timeout = Timeout.NONE

        /**
         * Ends the connection. Sends still queued are dropped, so callers must [flush] first: [NwSink.flush] (drain)
         * waits until every queued send has completed.
         */
        override fun close() = this@NwPeerConnection.close()
    }
}

/**
 * The connections Network.framework may still call back into. A connection registers when it starts and leaves once
 * it is cancelled and idle (see [NwPeerConnection.releaseIfIdle]). Without this, a completion that arrives after the
 * Kotlin side has dropped its last reference would call into freed state and abort the process.
 */
internal object CallbackRetention {
    private val lock = Lock()
    private val live = HashSet<NwPeerConnection>()

    /** How many connections are retained right now. */
    fun count(): Int = lock.withLock { live.size }

    fun retain(connection: NwPeerConnection) {
        lock.withLock { live.add(connection) }
    }

    fun release(connection: NwPeerConnection) {
        lock.withLock { live.remove(connection) }
    }
}

/** How the handshake ended. [Failed.waiting] is kept apart from a plain failure only for the message. */
internal sealed interface Handshake {
    data object Ready : Handshake

    class Failed(val waiting: Boolean, val code: Int?) : Handshake
}

/** What one blocking read gets back from its Network.framework receive. */
private sealed interface Reply {
    class Data(val bytes: ByteArray, val complete: Boolean) : Reply

    class Failed(val message: String) : Reply
}

/**
 * Runs the body of a Network.framework callback. A Kotlin exception must never leave a native callback, where it
 * would end the process, so [onFailure] receives it instead.
 */
@Suppress("TooGenericExceptionCaught")
private inline fun guardCallback(onFailure: (Throwable) -> Unit, body: () -> Unit) {
    try {
        body()
    } catch (error: Throwable) {
        onFailure(error)
    }
}

/** What a receive's completion needs: the connection to account for it and the waiter to wake. */
private class PendingReceive(val owner: NwPeerConnection, val waiter: OneShot<Reply>)

/**
 * The receive completion as a plain C function (see ms_connection_receive). Network.framework calls it on the
 * connection's queue exactly once per receive, cancelled or not. Nothing Objective-C reaches Kotlin here, so the
 * conversion that aborts the process on a cancelled receive cannot happen. The body must not throw into C.
 */
@Suppress("TooGenericExceptionCaught")
private val receiveCallback = staticCFunction<COpaquePointer?, COpaquePointer?, ULong, Int, Int, Int, Unit> {
        context,
        bytes,
        length,
        complete,
        hasError,
        errorCode,
    ->
    var pending: PendingReceive? = null
    try {
        val reference = checkNotNull(context).asStableRef<PendingReceive>()
        pending = reference.get()
        reference.dispose()
        val reply = if (hasError != 0) {
            Reply.Failed("Read failed with error $errorCode")
        } else {
            Reply.Data(copyOut(bytes, length.toInt()), complete != 0)
        }
        pending.waiter.offer(reply)
    } catch (error: Throwable) {
        pending?.waiter?.offer(Reply.Failed("The receive callback failed: ${error.message}"))
    } finally {
        pending?.owner?.endCallback()
    }
}

/** Copies [count] bytes from a native buffer into a new array. A null or empty source gives an empty array. */
private fun copyOut(source: COpaquePointer?, count: Int): ByteArray {
    val out = ByteArray(count)
    if (source != null && count > 0) {
        out.usePinned { memcpy(it.addressOf(0), source, count.convert()) }
    }
    return out
}

/** A dispatch_data copy of these bytes (the default destructor copies), so the Kotlin array can be reused. */
private fun ByteArray.toDispatchData(queue: dispatch_queue_t): dispatch_data_t {
    require(isNotEmpty()) { "A send of zero bytes" }
    return usePinned { pinned -> dispatch_data_create(pinned.addressOf(0), size.toULong(), queue, null) }
}
