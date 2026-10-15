/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.ProducerSession
import io.github.danyk20.mediasync.sync.engine.ServeResult
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.FakeSentLedger
import io.github.danyk20.mediasync.transport.lan.DeviceIdentity
import io.github.danyk20.mediasync.transport.rendezvous.FakeWorker
import io.github.danyk20.mediasync.transport.rendezvous.pairId
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal
import kotlin.random.Random
import mockwebserver3.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Internet sync on a device (docs/PLAN.md §8.4): two phones' links, here in one app, find each other through a fake
 * rendezvous Worker on this device and sync over WebRTC, their requests signed with their identities.
 */
@RunWith(AndroidJUnit4::class)
class InternetLinksTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val worker = FakeWorker(iceServers = "[]") // Host candidates: the phones are on one device.
    private val server = MockWebServer().apply {
        dispatcher = worker
        start()
    }
    private val url = server.url("/").toString()
    private val consumerIdentity = keystoreIdentity("mediasync-test-consumer")
    private val producerIdentity = keystoreIdentity("mediasync-test-producer")
    private val secret = ByteArray(32) { 3 }.toByteString()

    @After
    fun tearDown() {
        server.close()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.run {
            deleteEntry("mediasync-test-consumer")
            deleteEntry("mediasync-test-producer")
        }
    }

    /** An identity in the Android Keystore, as each phone has: requests are signed there. */
    private fun keystoreIdentity(alias: String): DeviceIdentity {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(X500Principal("CN=$alias"))
                    .build(),
            )
        }.generateKeyPair()
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return DeviceIdentity(store.getCertificate(alias) as X509Certificate, store.getKey(alias, null) as PrivateKey)
    }

    @Test
    fun aSyncGoesThroughTheWorkerOverWebRtc() {
        val noMobileData = object : MobileDataSource {
            override fun allowed() = false
        }
        val usage = MobileDataUsage(context, java.time.Clock.systemDefaultZone())
        val alerts = RelayAlerts(context)
        val tokens = PushTokens(context) // No Firebase in the test app: no token.
        val consumer = AndroidInternetLinks(context, { consumerIdentity }, noMobileData, alerts, usage, tokens)
        val producer = AndroidInternetLinks(context, { producerIdentity }, noMobileData, alerts, usage, tokens)
        val photos = List(4) { FakeMedia("p$it", Random(it).nextBytes(400_000)) }
        val sink = FakeMediaSink()
        val local = LocalDevice("Test phone", "test")
        val registry = FakePeerRegistry(PairedPeer(producerIdentity.id, "Anna's Pixel", DeviceRole.PRODUCER))
        val served = LinkedBlockingQueue<Result<ServeResult>>()
        val listening = consumer.listen(url, mapOf(producerIdentity.id to secret)) { connection ->
            served += runCatching {
                connection.use {
                    ConsumerSession(local, registry, FakeReceivedLedger(), sink, FakePairingGate(TOKEN)).serve(it)
                }
            }
        }
        try {
            worker.awaitOnline(pairId(consumerIdentity.id, producerIdentity.id), TIMEOUT_MILLIS)

            val result = producer.connect(consumerIdentity.id, url, secret).use { connection ->
                connection.peerId shouldBe consumerIdentity.id
                ProducerSession(local, FakeMediaSource(photos), FakeSentLedger()).sync(connection)
            }

            result.sent shouldBe 4
            served.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)!!.getOrThrow()
            sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
            worker.relayed.shouldNotBeEmpty()
        } finally {
            listening.close()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val KEYSTORE = "AndroidKeyStore"
        val TOKEN = "one-time-token!!".encodeToByteArray().toByteString()
    }
}
