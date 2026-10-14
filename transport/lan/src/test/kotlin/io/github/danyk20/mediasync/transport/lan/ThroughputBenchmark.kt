/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.ConsumerSession
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.ProducerSession
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.FakeSentLedger
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.kotest.matchers.shouldBe
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Sync throughput (docs/PLAN.md §7): whole sessions, from offer to the last confirmation, over real TLS on loopback
 * and over a simulated Wi-Fi hop. Run with `./gradlew :transport:lan:benchmark`; CI keeps the trend and flags
 * slowdowns. Loopback has no radio, so these numbers catch regressions in MediaSync, not Wi-Fi speeds.
 */
@Tag("benchmark")
class ThroughputBenchmark {

    private val consumerIdentity = testIdentity("Receiving phone")
    private val producerIdentity = testIdentity("Sending phone")
    private val executor = Executors.newCachedThreadPool()

    private class Result(val name: String, val megabytesPerSecond: Double)

    @Test
    fun throughput() {
        overTls(List(20) { 100 * KB }) // Warm-up: JIT and TLS.
        val results = listOf(
            Result("One 128 MB video over TLS", best { overTls(listOf(128 * MB)) }),
            Result("500 photos of 200 kB over TLS", best { overTls(List(500) { 200 * KB }) }),
            Result("300 photos of 100 kB, 5 ms Wi-Fi latency", best { overLatentLink(List(300) { 100 * KB }, 5) }),
        )
        executor.shutdownNow()

        results.forEach { println(String.format(Locale.ROOT, "%-45s %8.1f MB/s", it.name, it.megabytesPerSecond)) }
        val json = results.joinToString(",\n", "[\n", "\n]\n") {
            String.format(
                Locale.ROOT,
                """  {"name": "%s", "unit": "MB/s", "value": %.1f}""",
                it.name,
                it.megabytesPerSecond,
            )
        }
        System.getProperty("benchmark.output")?.let { File(it).apply { parentFile.mkdirs() }.writeText(json) }
    }

    /** The best of three runs: the fastest is the least disturbed by the machine. */
    private fun best(run: () -> Double): Double = List(RUNS) { run() }.max()

    private fun overTls(sizes: List<Int>): Double {
        val media = media(sizes)
        val server = LanServer(consumerIdentity, { true })
        val sink = FakeMediaSink()
        val served = executor.submit(Callable { server.accept().handshake().use { consumer(sink).serve(it) } })
        val start = System.nanoTime()
        server.use {
            LanClient.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.port),
                producerIdentity,
                consumerIdentity.id,
            ).use { connection -> producer(media).sync(connection).sent shouldBe sizes.size }
            served.get()
        }
        return megabytesPerSecond(sizes, System.nanoTime() - start)
    }

    private fun overLatentLink(sizes: List<Int>, latencyMillis: Long): Double {
        val media = media(sizes)
        val connection = ConnectionPair(producerIdentity.id, consumerIdentity.id, latencyMillis = latencyMillis)
        val start = System.nanoTime()
        val (sent, _) = runBothSides(
            producer = { producer(media).sync(connection.producerEnd) },
            consumer = { consumer(FakeMediaSink()).serve(connection.consumerEnd) },
        )
        sent.getOrThrow().sent shouldBe sizes.size
        return megabytesPerSecond(sizes, System.nanoTime() - start)
    }

    private fun producer(media: List<FakeMedia>) =
        ProducerSession(LocalDevice("Sending phone", "benchmark"), FakeMediaSource(media), FakeSentLedger())

    private fun consumer(sink: FakeMediaSink) = ConsumerSession(
        LocalDevice("Receiving phone", "benchmark"),
        FakePeerRegistry(PairedPeer(producerIdentity.id, "Sending phone", DeviceRole.PRODUCER)),
        FakeReceivedLedger(),
        sink,
        FakePairingGate("unused-token-xx!".encodeUtf8()),
    )

    private fun media(sizes: List<Int>) = sizes.mapIndexed { i, size -> FakeMedia("m$i", Random(i).nextBytes(size)) }

    private fun megabytesPerSecond(sizes: List<Int>, nanos: Long) =
        sizes.sumOf { it.toLong() } / MB.toDouble() / (nanos / NANOS_PER_SECOND)

    private companion object {
        const val KB = 1024
        const val MB = 1024 * KB
        const val RUNS = 3
        const val NANOS_PER_SECOND = 1e9
    }
}
