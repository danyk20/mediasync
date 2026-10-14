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
import io.github.danyk20.mediasync.sync.protocol.TurboReady
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
