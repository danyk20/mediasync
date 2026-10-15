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
import io.github.danyk20.mediasync.sync.protocol.TurboReady
import io.github.danyk20.mediasync.sync.protocol.WireRole
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
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
                TurboHost {
                    opened++
                    host.open()
                }
            },
        )

    private fun request(consumer: ConsumerSession): Pair<Result<TurboAnswer>, Result<ServeResult>> {
        val connection = ConnectionPair(producerId, consumerId)
        return runBothSides(
            producer = { TurboSetup(local).request(connection.producerEnd) },
            consumer = { consumer.serve(connection.consumerEnd) },
        )
    }

    @Test
    fun aPairedPhoneLearnsHowToJoinTheDirectLink() {
        val (answer, served) = request(consumer())

        answer.getOrThrow() shouldBe TurboAnswer.Ready(link)
        served.getOrThrow() shouldBe ServeResult.TurboAnswered(TurboAnswer.Ready(link))
        opened shouldBe 1
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
    fun aReasonFromALaterVersionIsAFailure() {
        TurboReady(status = 42).toAnswer() shouldBe TurboAnswer.Unavailable(TurboUnavailable.FAILED)
    }

    @Test
    fun turboReceiveNetworksAreDerivedFromTheKeyAndChangeWithEachEpoch() {
        val key = ByteArray(TURBO_KEY_BYTES) { 7 }.toByteString()
        val other = ByteArray(TURBO_KEY_BYTES) { 8 }.toByteString()

        val (name, passphrase) = DirectLink.derived(key, epoch = 1_000)

        DirectLink.derived(key, epoch = 1_000) shouldBe (name to passphrase) // Both phones get the same.
        name shouldMatch Regex("DIRECT-[A-Za-z0-9]{2}-MediaSync-[A-Za-z0-9]{6}")
        name.length shouldBeLessThanOrEqual 32 // Wi-Fi network names are at most 32 bytes.
        passphrase shouldMatch Regex("[A-Za-z0-9]{24}")
        DirectLink.derived(key, epoch = 1_001).first shouldNotBe name
        DirectLink.derived(key, epoch = 1_001).second shouldNotBe passphrase
        DirectLink.derived(other, epoch = 1_000).second shouldNotBe passphrase
        DirectLink.epochAt(DirectLink.EPOCH_MILLIS * 1_000 + 1) shouldBe 1_000
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
    fun eachTransferGetsFreshCredentialsAndThePassphraseIsNeverLogged() {
        val all = List(20) { DirectLink.credentials() }

        all.forEach { (name, passphrase) ->
            name shouldMatch Regex("DIRECT-[A-Za-z0-9]{2}-MediaSync")
            passphrase shouldMatch Regex("[A-Za-z0-9]{24}")
        }
        all.map { it.second }.toSet() shouldHaveSize 20
        link.toString() shouldNotContain link.passphrase
    }
}
