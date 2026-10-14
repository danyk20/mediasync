/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.IOException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

/** The phones' signaling through the Worker's mailbox: sealed with their pair key, relayed unread (ADR 0009 §4). */
class SignalingTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = "000102030405060708090a0b0c".decodeHex().toByteArray(),
            info = "f0f1f2f3f4f5f6f7f8f9".decodeHex().toByteArray(),
            length = 42,
        )

        okm.toByteString().hex() shouldBe
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    }

    @Test
    fun onlyThePairsOtherPhoneCanOpenItsMessages() {
        val cipher = SignalingCipher(secret, "pair-a")
        val offer = "v=0 offer".encodeToByteArray()

        val sealed = cipher.seal(offer)

        SignalingCipher(secret, "pair-a").open(sealed).decodeToString() shouldBe "v=0 offer"
        sealed.toByteString() shouldNotBe cipher.seal(offer).toByteString() // A fresh nonce each time.
        shouldThrow<IOException> { SignalingCipher(secret, "pair-b").open(sealed) } // Replayed into another pair.
        shouldThrow<IOException> { SignalingCipher(ByteArray(32), "pair-a").open(sealed) }
        shouldThrow<IOException> { cipher.open(sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }) }
        shouldThrow<IOException> { cipher.open(byteArrayOf(1, 2, 3)) }
        shouldThrow<IllegalArgumentException> { SignalingCipher(ByteArray(16), "pair-a") }
    }

    @Test
    fun theMailboxIsSignedAndRelaysNoticesAndSealedMessages() {
        val serverSide = LinkedBlockingQueue<Any>()
        var serverSocket: WebSocket? = null
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            serverSocket = webSocket
                            webSocket.send("""{"type":"ready","peerOnline":true,"queued":2}""")
                            webSocket.send(byteArrayOf(7, 7).toByteString())
                            webSocket.send("""{"type":"peer","online":false}""")
                            webSocket.send("""{"type":"error","code":"mailbox_full"}""")
                            webSocket.send("pong")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            serverSide += text
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            serverSide += bytes
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            serverSide += "closing $code"
                            webSocket.close(code, null)
                        }
                    },
                ).build(),
            )
            server.start()
            val events = LinkedBlockingQueue<String>()
            val signer = RendezvousSigner(byteArrayOf(1), { it.copyOf(4) }, clock = { 1_000_000 })
            val mailbox = SignalingMailbox(URI(server.url("/").toString()), signer, pingIntervalMillis = 50)

            val connection = mailbox.open(
                "p",
                object : SignalingMailbox.Listener {
                    override fun onReady(peerOnline: Boolean, queued: Int) {
                        events += "ready $peerOnline $queued"
                    }

                    override fun onPeer(online: Boolean) {
                        events += "peer $online"
                    }

                    override fun onMessage(message: ByteArray) {
                        events += "message ${message.toList()}"
                    }

                    override fun onError(code: String) {
                        events += "error $code"
                    }
                },
            )

            List(4) { events.poll(5, TimeUnit.SECONDS) } shouldBe
                listOf("ready true 2", "message [7, 7]", "peer false", "error mailbox_full")
            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
            upgrade.url.encodedPath shouldBe "/v1/pairs/p/signal"
            upgrade.headers[RendezvousSigner.SIGNATURE] shouldNotBe null
            upgrade.headers[RendezvousSigner.TIMESTAMP] shouldBe "1000"

            connection.send(byteArrayOf(1, 2, 3)) shouldBe true
            generateSequence { serverSide.poll(5, TimeUnit.SECONDS) }
                .first { it is ByteString } shouldBe byteArrayOf(1, 2, 3).toByteString()
            generateSequence { serverSide.poll(5, TimeUnit.SECONDS) }.first { it == "ping" } shouldBe "ping"

            connection.close()
            generateSequence { serverSide.poll(5, TimeUnit.SECONDS) }
                .first { it.toString().startsWith("closing") } shouldBe "closing 1000"
            serverSocket shouldNotBe null
        }
    }
}
