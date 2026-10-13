/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.transport.lan.LanClient
import io.github.danyk20.mediasync.transport.lan.LanServer
import io.github.danyk20.mediasync.transport.lan.LanTimeouts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on real Android (API 29 and 36 in CI): Keystore keys must work for TLS 1.3 client and server
 * authentication through the platform's Conscrypt, which differs between Android versions.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreIdentityTest {

    private val consumer = KeystoreIdentityProvider("test-consumer-identity")
    private val producer = KeystoreIdentityProvider("test-producer-identity")
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        consumer.delete()
        producer.delete()
        executor.shutdownNow()
    }

    @Test
    fun identityIsStableAcrossInstances() {
        val first = consumer.identity().id

        KeystoreIdentityProvider("test-consumer-identity").identity().id shouldBe first
    }

    @Test
    fun differentAliasesAreDifferentDevices() {
        consumer.identity().id shouldNotBe producer.identity().id
    }

    @Test
    fun keystoreKeysAuthenticateBothSidesOfTls13() {
        val timeouts = LanTimeouts(connectMillis = 5_000, readMillis = 10_000)
        LanServer(consumer.identity(), { it == producer.identity().id }, timeouts = timeouts).use { server ->
            val accepted = executor.submit<String> {
                server.accept().handshake().use { connection ->
                    connection.peerId shouldBe producer.identity().id
                    connection.source.readUtf8(5)
                }
            }

            LanClient.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.port),
                producer.identity(),
                expectedPeer = consumer.identity().id,
                timeouts,
            ).use { client ->
                client.peerId shouldBe consumer.identity().id
                client.sink.write("hello".encodeUtf8()).flush()
                accepted.get(10, TimeUnit.SECONDS) shouldBe "hello"
            }
        }
    }
}
