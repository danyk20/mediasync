/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSink
import io.github.danyk20.mediasync.sync.engine.testing.FakePairingGate
import io.github.danyk20.mediasync.sync.engine.testing.FakePeerRegistry
import io.github.danyk20.mediasync.sync.engine.testing.FakeReceivedLedger
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.github.danyk20.mediasync.sync.engine.testing.runBothSides
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

/** Asking the receiving phone for a direct link (docs/PLAN.md §8.3). */
class TurboTest {

    private val producerId = deviceId(1)
    private val consumerId = deviceId(2)
    private val local = LocalDevice("Test phone", "0.0.0-test")
    private val link = DirectLink("DIRECT-ab-MediaSync", "secret-passphrase-123456", "192.168.49.1", 47_420)
    private var opened = 0
    private var askedForAware: Boolean? = null

    private fun consumer(paired: Boolean = true, turbo: TurboHost? = TurboHost { TurboAnswer.Ready(link) }) =
        ConsumerSession(
            local,
            if (paired) {
                FakePeerRegistry(
                    PairedPeer(producerId, "Producer", DeviceRole.PRODUCER),
                )
            } else {
                FakePeerRegistry()
            },
            FakeReceivedLedger(),
            FakeMediaSink(),
            FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
            turbo = turbo?.let { host ->
                TurboHost { aware ->
                    opened++
                    askedForAware = aware
                    host.open(aware)
                }
            },
        )

    private fun request(
        consumer: ConsumerSession,
        aware: Boolean = false,
    ): Pair<Result<TurboAnswer>, Result<ServeResult>> {
        val connection = ConnectionPair(producerId, consumerId)
        return runBothSides(
            producer = { TurboSetup(local).request(connection.producerEnd, aware) },
            consumer = { consumer.serve(connection.consumerEnd) },
        )
    }

    @Test
    fun aPairedPhoneLearnsHowToJoinTheDirectLink() {
        val (answer, served) = request(consumer())

        answer.getOrThrow() shouldBe TurboAnswer.Ready(link)
        served.getOrThrow() shouldBe ServeResult.TurboAnswered(TurboAnswer.Ready(link))
        opened shouldBe 1
        askedForAware shouldBe false
    }

    @Test
    fun aPhoneThatCanUseWifiAwareSaysSoAndLearnsTheAwareLink() {
        val aware =
            DirectLink("MediaSync-abcdefghijklmnop", "secret-passphrase-123456", "", 47_420, LinkKind.WIFI_AWARE)

        val (answer, _) = request(consumer(turbo = { TurboAnswer.Ready(aware) }), aware = true)

        askedForAware shouldBe true
        answer.getOrThrow() shouldBe TurboAnswer.Ready(aware)
        aware.toString() shouldNotContain aware.passphrase
    }

    @Test
    fun aPhoneThatCantOpenOneSaysWhy() {
        TurboUnavailable.entries.forEach { reason ->
            val (answer, _) = request(consumer(turbo = { TurboAnswer.Unavailable(reason) }))

            answer.getOrThrow() shouldBe TurboAnswer.Unavailable(reason)
        }
        request(consumer(turbo = null)).first.getOrThrow() shouldBe
            TurboAnswer.Unavailable(TurboUnavailable.UNSUPPORTED)
    }

    @Test
    fun anUnpairedPhoneIsNotGivenALink() {
        val (answer, served) = request(consumer(paired = false))

        answer.exceptionOrNull().shouldBeInstanceOf<PeerEndedSessionException>().byeReason shouldBe
            Bye.Reason.NOT_PAIRED
        served.getOrThrow() shouldBe ServeResult.Rejected(Bye.Reason.NOT_PAIRED)
        opened shouldBe 0
    }

    @Test
    fun onlyAPairedProducerLearnsWhereTheConsumerIsOnItsWifi() {
        val addresses = listOf("192.168.1.20:47420", "[fd00::20]:47420")
        val onWifi = local.copy(wifiAddresses = addresses)
        fun helloFrom(paired: Boolean): Hello {
            val connection = ConnectionPair(producerId, consumerId)
            val registry =
                if (paired) {
                    FakePeerRegistry(
                        PairedPeer(producerId, "Producer", DeviceRole.PRODUCER),
                    )
                } else {
                    FakePeerRegistry()
                }
            val consumer = ConsumerSession(
                onWifi,
                registry,
                FakeReceivedLedger(),
                FakeMediaSink(),
                FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
            )
            return runBothSides(
                producer = {
                    val channel = FrameChannel(connection.producerEnd)
                    channel.send(Hello(PROTOCOL_VERSION, "Producer", WireRole.PRODUCER, "test"))
                    channel.expect<Hello>().also { channel.send(Bye()) }
                },
                consumer = { runCatching { consumer.serve(connection.consumerEnd) } },
            ).first.getOrThrow()
        }

        helloFrom(paired = true).wifiAddresses shouldBe addresses
        helloFrom(paired = false).wifiAddresses shouldBe emptyList()
    }

    @Test
    fun aProducerKeepsAFewShortWifiAddressesOnly() {
        fun told(vararg addresses: String) = wifiAddressesOf(
            Hello(PROTOCOL_VERSION, "Pixel 2 XL", WireRole.CONSUMER, wifiAddresses = addresses.toList()),
        )

        told() shouldBe null // On no Wi-Fi, or an older version: nothing changes.
        told("", " ") shouldBe null
        told("192.168.1.20:47420", "192.168.1.20:47420") shouldBe listOf("192.168.1.20:47420")
        told("[fd00:0000:0000:0000:0000:0000:0000:0020]:47420")?.size shouldBe 1
        told("x".repeat(48)) shouldBe null
        told("10.0.0.1:1", "10.0.0.2:1", "10.0.0.3:1", "10.0.0.4:1", "10.0.0.5:1")?.size shouldBe 4
    }

    @Test
    fun onlyAPairedProducerLearnsTheTurboKey() {
        val key = ByteArray(TURBO_KEY_BYTES) { 3 }.toByteString()
        val keyed = local.copy(turboKey = key)
        fun helloFrom(paired: Boolean): Hello {
            val connection = ConnectionPair(producerId, consumerId)
            val registry =
                if (paired) {
                    FakePeerRegistry(
                        PairedPeer(producerId, "Producer", DeviceRole.PRODUCER),
                    )
                } else {
                    FakePeerRegistry()
                }
            val consumer = ConsumerSession(
                keyed,
                registry,
                FakeReceivedLedger(),
                FakeMediaSink(),
                FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
            )
            return runBothSides(
                producer = {
                    val channel = FrameChannel(connection.producerEnd)
                    channel.send(Hello(PROTOCOL_VERSION, "Producer", WireRole.PRODUCER, "test"))
                    channel.expect<Hello>().also { channel.send(Bye()) }
                },
                consumer = { runCatching { consumer.serve(connection.consumerEnd) } },
            ).first.getOrThrow()
        }

        helloFrom(paired = true).turboKey shouldBe key
        helloFrom(paired = false).turboKey shouldBe ByteString.EMPTY
        keyed.toString() shouldNotContain key.hex()
    }

    @Test
    fun aPairedProducerGetsItsPairSecretInEverySessionAndAnUnpairedOneNone() {
        val registry = FakePeerRegistry(PairedPeer(producerId, "Producer", DeviceRole.PRODUCER), pairSecrets = true)
        fun secretFrom(registry: FakePeerRegistry): ByteString {
            val connection = ConnectionPair(producerId, consumerId)
            val consumer = ConsumerSession(
                local,
                registry,
                FakeReceivedLedger(),
                FakeMediaSink(),
                FakePairingGate("one-time-token!!".encodeToByteArray().toByteString()),
            )
            return runBothSides(
                producer = {
                    val channel = FrameChannel(connection.producerEnd)
                    channel.send(Hello(PROTOCOL_VERSION, "Producer", WireRole.PRODUCER, "test"))
                    channel.expect<Hello>().also { channel.send(Bye()) }.pairSecret
                },
                consumer = { runCatching { consumer.serve(connection.consumerEnd) } },
            ).first.getOrThrow()
        }

        val first = secretFrom(registry)

        first.size shouldBe PAIR_SECRET_BYTES
        secretFrom(registry) shouldBe first // The same every session.
        secretFrom(FakePeerRegistry(pairSecrets = true)) shouldBe ByteString.EMPTY
    }
}
