/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A rendezvous Worker (ADR 0009) as far as internet sync uses it: pairs, ICE servers, wake-ups, and mailboxes that
 * relay between the pair's phones. It checks no signatures.
 */
class FakeWorker(
    /** The ICE servers it hands out, as JSON: by default a STUN server. */
    private val iceServers: String = """[{"urls":["stun:stun.example"]}]""",
) : Dispatcher() {
    val registered: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val relayed = CopyOnWriteArrayList<ByteString>()
    val wakes = AtomicInteger()
    val mailboxesOpened = AtomicInteger()

    /** The consumer's pair registrations, as sent (JSON): the producer, and its FCM token if any. */
    val registrations = CopyOnWriteArrayList<String>()

    /** The live relay reports, as sent (JSON). */
    val relayReports = CopyOnWriteArrayList<String>()

    @Volatile
    var onWake: () -> Unit = {}

    @Volatile
    var wakeRefusal: String? = null
    private val sockets = ConcurrentHashMap<String, MutableList<WebSocket>>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val pair = path.removePrefix("/v1/pairs/").substringBefore('/')
        return when {
            path.endsWith("/signal") -> MockResponse.Builder().webSocketUpgrade(Relay(pair)).build()

            pair !in registered && request.method != "PUT" -> refusal(404, "unpaired")

            request.method == "PUT" -> {
                registered += pair
                registrations += request.body?.utf8().orEmpty()
                json("""{"pairId":"$pair","created":true}""")
            }

            path.endsWith("/ice-servers") ->
                json("""{"iceServers":$iceServers,"ttlSeconds":3600,"relay":{"available":true}}""")

            path.endsWith("/relay-usage") && request.method == "POST" -> {
                relayReports += request.body?.utf8().orEmpty()
                json(METER)
            }

            path.endsWith("/relay-usage") -> json(METER)

            path.endsWith("/wake") -> {
                wakes.incrementAndGet()
                wakeRefusal?.let { refusal(409, it) } ?: json("{}").also { onWake() }
            }

            else -> refusal(404, "not_found")
        }
    }

    /** Waits until a phone has the mailbox of [pair] open. */
    fun awaitOnline(pair: String, timeoutMillis: Long = 5_000) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (sockets[pair].orEmpty().isEmpty()) {
            check(System.currentTimeMillis() < end) { "Nobody came online for $pair" }
            Thread.sleep(10)
        }
    }

    fun dropAll() = sockets.values.forEach { open ->
        synchronized(open) { open.toList() }.forEach { it.close(1012, "restarting") }
        synchronized(open) { open.clear() }
    }

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun refusal(status: Int, code: String) =
        MockResponse.Builder().code(status).body("""{"error":{"code":"$code","message":"No"}}""").build()

    private inner class Relay(private val pair: String) : WebSocketListener() {
        private fun open() = sockets.getOrPut(pair) { mutableListOf() }

        private fun others(socket: WebSocket) = synchronized(open()) { open().filter { it !== socket } }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            mailboxesOpened.incrementAndGet()
            val others = others(webSocket)
            synchronized(open()) { open() += webSocket }
            webSocket.send("""{"type":"ready","peerOnline":${others.isNotEmpty()},"queued":0}""")
            others.forEach { it.send("""{"type":"peer","online":true}""") }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            relayed += bytes
            others(webSocket).forEach { it.send(bytes) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") webSocket.send("pong")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(open()) { open().remove(webSocket) }
            webSocket.close(code, null)
        }
    }

    private companion object {
        /** 312 GB of a 950 GB budget used in October. */
        const val METER = """{"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},""" +
            """"usedBytes":312000000000,"authoritativeBytes":310000000000,"liveBytes":2000000000,""" +
            """"limitBytes":950000000000,"freeAllowanceBytes":1000000000000,"relayAvailable":true}"""
    }
}
