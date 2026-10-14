/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A pair's signaling mailbox at the Worker (ADR 0009 §4): one WebSocket per phone, through which the two exchange their
 * sealed WebRTC offers, answers and candidates. The Worker relays them unread, and holds them for a few minutes while
 * the other phone is offline. A text `ping` keeps the socket open without waking the mailbox.
 */
class SignalingMailbox(
    private val baseUrl: URI,
    private val signer: RendezvousSigner,
    private val client: OkHttpClient = OkHttpClient(),
    private val pingIntervalMillis: Long = PING_INTERVAL_MILLIS,
) {
    /** What the mailbox tells this phone; called on OkHttp's thread. */
    interface Listener {
        /** Connected: whether the other phone is too, and how many messages waited for this one. */
        fun onReady(peerOnline: Boolean, queued: Int) = Unit

        fun onPeer(online: Boolean) = Unit

        /** A sealed message from the other phone, as it sent it. */
        fun onMessage(message: ByteArray)

        /** `binary_only`, `message_too_large`, `mailbox_full` or `unpaired`. */
        fun onError(code: String) = Unit

        /** 4001: replaced by a newer connection of this phone; 4003: unpaired; 1008: too many messages. */
        fun onClosed(code: Int, reason: String) = Unit

        fun onFailure(error: Throwable) = Unit
    }

    /** An open mailbox: [send] queues a sealed message; `false` once it's closing. */
    class Connection internal constructor(private val socket: WebSocket, private val pinger: ScheduledFuture<*>) {
        fun send(message: ByteArray): Boolean = socket.send(message.toByteString())

        fun close() {
            pinger.cancel(false)
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    fun open(pairId: String, listener: Listener): Connection {
        val url = baseUrl.resolve("/v1/pairs/$pairId/signal")
        val request = Request.Builder().url(url.toString()).apply {
            signer.headers("GET", url).forEach { (name, value) -> header(name, value) }
        }.build()
        val socket = client.newWebSocket(request, Events(listener))
        val pinger = PINGS.scheduleAtFixedRate(
            { socket.send(PING) },
            pingIntervalMillis,
            pingIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return Connection(socket, pinger)
    }

    private inner class Events(private val listener: Listener) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == PONG) return
            val notice = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (notice["type"]?.jsonPrimitive?.content) {
                "ready" -> listener.onReady(
                    peerOnline = notice["peerOnline"]?.jsonPrimitive?.boolean ?: false,
                    queued = notice["queued"]?.jsonPrimitive?.int ?: 0,
                )

                "peer" -> listener.onPeer(notice["online"]?.jsonPrimitive?.boolean ?: false)

                "error" -> listener.onError(notice["code"]?.jsonPrimitive?.content ?: "unknown")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Refused for a clock that's off: the next connection is signed with the Worker's time.
            response?.header(RendezvousSigner.SERVER_TIME)?.toLongOrNull()?.let(signer::adjustClock)
            listener.onFailure(t)
        }
    }

    private companion object {
        /** ADR 0009: every 30 to 60 seconds. */
        const val PING_INTERVAL_MILLIS = 45_000L
        const val NORMAL_CLOSURE = 1000
        const val PING = "ping"
        const val PONG = "pong"
        val JSON = Json { ignoreUnknownKeys = true }
        val PINGS: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mediasync-mailbox-ping").apply { isDaemon = true }
        }
    }
}
