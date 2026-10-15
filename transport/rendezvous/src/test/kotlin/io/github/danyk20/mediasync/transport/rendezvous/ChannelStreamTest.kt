/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

/** The sync engine's byte streams over a message channel such as WebRTC's (PLAN §8.4). */
class ChannelStreamTest {

    /** Delivers each message to [other] at once; records their sizes. */
    private class Loopback : MessageChannel {
        lateinit var other: ChannelStream
        val sizes = CopyOnWriteArrayList<Int>()
        val queued = AtomicLong()
        var closed = false

        override fun send(message: ByteArray): Boolean {
            if (closed) return false
            sizes += message.size
            other.onMessage(message)
            return true
        }

        override fun bufferedAmount() = queued.get()

        override fun close() {
            closed = true
            other.onClosed()
        }
    }

    private fun pair(timeoutMillis: Long = 10_000): Pair<ChannelStream, ChannelStream> {
        val aToB = Loopback()
        val bToA = Loopback()
        val a = ChannelStream(aToB, timeoutMillis = timeoutMillis)
        val b = ChannelStream(bToA, timeoutMillis = timeoutMillis)
        aToB.other = b
        bToA.other = a
        return a to b
    }

    @Test
    fun bytesArriveInOrderInMessagesOfAtMost64KiB() {
        val channel = Loopback()
        val receiver = ChannelStream(Loopback())
        channel.other = receiver
        val sender = ChannelStream(channel)
        val bytes = Random(1).nextBytes(3 * 1024 * 1024 + 123)

        sender.sink.write(bytes).flush()

        receiver.source.readByteArray(bytes.size.toLong()).toByteString() shouldBe bytes.toByteString()
        channel.sizes.max() shouldBeLessThanOrEqual 64 * 1024
        channel.sizes.sum() shouldBe bytes.size
    }

    @Test
    fun aWriterWaitsWhileTooMuchIsQueued() {
        val channel = Loopback()
        channel.other = ChannelStream(Loopback())
        val sender = ChannelStream(channel, highWaterBytes = 1_000)
        channel.queued.set(5_000) // The radio hasn't caught up.
        val wrote = CountDownLatch(1)

        thread {
            sender.sink.write(ByteArray(10)).flush()
            wrote.countDown()
        }

        wrote.await(300, TimeUnit.MILLISECONDS) shouldBe false
        channel.queued.set(0)
        sender.onBufferedAmountChange()
        wrote.await(5, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun readsEndAfterWhatArrivedWhenTheOtherSideCloses() {
        val (a, b) = pair()

        a.sink.writeUtf8("last words").flush()
        a.close()

        b.source.readUtf8() shouldBe "last words"
        b.source.exhausted() shouldBe true
        shouldThrow<IOException> { a.sink.writeUtf8("more").flush() }
    }

    @Test
    fun aFailedChannelFailsReadsAndASilentOneTimesOut() {
        val (a, b) = pair(timeoutMillis = 100)

        shouldThrow<SocketTimeoutException> { b.source.readByte() }
        a.onClosed(IOException("ICE failed"))
        shouldThrow<IOException> { a.source.readByte() }.message shouldBe "ICE failed"
    }

    @Test
    fun aWholeSyncRunsOverIt() {
        val (producerSide, consumerSide) = pair()
        val producerId = deviceId(1)
        val consumerId = deviceId(2)
        val photos = List(5) { FakeMedia("p$it", Random(it).nextBytes(400_000)) }
        val sink = FakeMediaSink()
        val local = LocalDevice("Test phone", "test")

        val (sent, served) = runBothSides(
            producer = {
                ProducerSession(local, FakeMediaSource(photos), FakeSentLedger())
                    .sync(ChannelPeerConnection(consumerId, producerSide, "webrtc"))
            },
            consumer = {
                ConsumerSession(
                    local,
                    FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER)),
                    FakeReceivedLedger(),
                    sink,
                    FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
                ).serve(ChannelPeerConnection(producerId, consumerSide, "webrtc"))
            },
        )

        sent.getOrThrow().sent shouldBe 5
        served.getOrThrow()
        sink.storedContents() shouldBe photos.map { it.bytes.toByteString() }.toSet()
    }
}
