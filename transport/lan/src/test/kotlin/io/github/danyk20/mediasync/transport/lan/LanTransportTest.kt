/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.transport.PeerConnection
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class LanTransportTest {

    private val consumer = testIdentity("consumer")
    private val producer = testIdentity("producer")
    private val stranger = testIdentity("stranger")
    private val executor = Executors.newCachedThreadPool()
    private val timeouts = LanTimeouts(connectMillis = 2_000, readMillis = 5_000)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun server(trusted: Set<io.github.danyk20.mediasync.core.model.DeviceId>) =
        LanServer(consumer, { it in trusted }, timeouts = timeouts)

    private fun LanServer.acceptAsync(): Future<PeerConnection> = executor.submit(Callable { accept().handshake() })

    private fun LanServer.address() = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    @Test
    fun `paired devices connect and see each other's identity`() {
        server(setOf(producer.id)).use { server ->
            val accepted = server.acceptAsync()

            val client = LanClient.connect(server.address(), producer, expectedPeer = consumer.id, timeouts)
            val serverSide = accepted.get(5, TimeUnit.SECONDS)

            client.peerId shouldBe consumer.id
            serverSide.peerId shouldBe producer.id
            serverSide.remoteAddress shouldStartWith "127.0.0.1:"
            client.shouldBeInstanceOf<SocketPeerConnection>().protocol shouldBe "TLSv1.3"
            client.close()
            serverSide.close()
        }
    }

    @Test
    fun `bytes flow both ways`() {
        server(setOf(producer.id)).use { server ->
            val accepted = server.acceptAsync()
            LanClient.connect(server.address(), producer, consumer.id, timeouts).use { client ->
                accepted.get(5, TimeUnit.SECONDS).use { serverSide ->
                    client.sink.write("hello consumer".encodeUtf8()).flush()
                    serverSide.source.readByteString(14) shouldBe "hello consumer".encodeUtf8()

                    serverSide.sink.write("hello producer".encodeUtf8()).flush()
                    client.source.readByteString(14) shouldBe "hello producer".encodeUtf8()
                }
            }
        }
    }

    @Test
    fun `an unknown device is turned away by the consumer`() {
        server(setOf(producer.id)).use { server ->
            val accepted = server.acceptAsync()

            // TLS 1.3 may report the server's rejection on the client's first read rather than in connect().
            shouldThrowAny {
                LanClient.connect(server.address(), stranger, consumer.id, timeouts).use { it.source.readByte() }
            }
            shouldThrowAny { accepted.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `the producer refuses an impostor that doesn't hold the paired consumer's key`() {
        LanServer(stranger, { true }, timeouts = timeouts).use { impostor ->
            val accepted = impostor.acceptAsync()

            shouldThrowAny {
                LanClient.connect(impostor.address(), producer, expectedPeer = consumer.id, timeouts)
            }
            shouldThrowAny { accepted.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `connecting to a closed port fails cleanly`() {
        val port = server(emptySet()).use { it.port }

        shouldThrowAny {
            LanClient.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                producer,
                consumer.id,
                timeouts,
            )
        }.shouldBeInstanceOf<IOException>()
    }

    @Test
    fun `each device's id is the hash of its own certificate`() {
        producer.id shouldBe producer.certificate.deviceId()
        producer.id shouldNotBe consumer.id
    }
}
