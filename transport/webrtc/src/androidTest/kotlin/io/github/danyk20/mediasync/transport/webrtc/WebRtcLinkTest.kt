/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.webrtc

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.ProducerSession
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.FakeSentLedger
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.github.danyk20.mediasync.transport.rendezvous.Signal
import io.kotest.matchers.shouldBe
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.junit.runner.RunWith

/** Two phones' WebRTC links, here in one app over the device's own network, set up by their signals (PLAN §8.4). */
@RunWith(AndroidJUnit4::class)
class WebRtcLinkTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)

    /** What one side sends, the other receives: the Worker's mailbox in miniature. */
    private class Pipe(override val incoming: BlockingQueue<Signal> = LinkedBlockingQueue()) : SignalPipe {
        lateinit var other: Pipe

        override fun send(signal: Signal) {
            other.incoming += signal
        }
    }

    @Test
    fun aWholeSyncRunsOverAWebRtcDataChannel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val producerPipe = Pipe()
        val consumerPipe = Pipe()
        producerPipe.other = consumerPipe
        consumerPipe.other = producerPipe
        val consumerQueue = LinkedBlockingQueue<Signal>()
        // The consumer waits for the offer, as it would in its mailbox; the rest goes to its link.
        val consumerSide = object : SignalPipe {
            override val incoming: BlockingQueue<Signal> = consumerQueue

            override fun send(signal: Signal) = consumerPipe.send(signal)
        }
        val pool = Executors.newCachedThreadPool()
        try {
            val producerLink = pool.submit(
                Callable { WebRtcLink(context).connect(consumerId, emptyList(), producerPipe, TIMEOUT_MILLIS) },
            )
            val offer = generateSequence { consumerPipe.incoming.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
                .first { it is Signal.Offer } as Signal.Offer
            val forward = pool.submit { while (true) consumerQueue += consumerPipe.incoming.take() }
            val consumerConnection = WebRtcLink(
                context,
            ).accept(producerId, offer, emptyList(), consumerSide, TIMEOUT_MILLIS)
            val producerConnection = producerLink.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            forward.cancel(true)

            val photos = List(6) { FakeMedia("p$it", Random(it).nextBytes(500_000)) }
            val sink = FakeMediaSink()
            val local = LocalDevice("Test phone", "test")
            val (sent, served) = runBothSides(
                producer = {
                    ProducerSession(local, FakeMediaSource(photos), FakeSentLedger()).sync(producerConnection)
                },
                consumer = {
                    ConsumerSession(
                        local,
                        FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER)),
                        FakeReceivedLedger(),
                        sink,
                        FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
                    ).serve(consumerConnection)
                },
            )

            sent.getOrThrow().sent shouldBe 6
            served.getOrThrow()
            sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
            producerConnection.close()
            consumerConnection.close()
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
