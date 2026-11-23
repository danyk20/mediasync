/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.protocol.TurboReady
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** The pure part of Turbo (docs/PLAN.md §8.3): link names, passphrases and answers, with no session. */
class TurboLinkTest {

    private val link = DirectLink("DIRECT-ab-MediaSync", "secret-passphrase-123456", "192.168.49.1", 47_420)

    @Test
    fun aReasonFromALaterVersionIsAFailure() {
        TurboReady(status = 42).toAnswer() shouldBe TurboAnswer.Unavailable(TurboUnavailable.FAILED)
    }

    @Test
    fun aKindOfLinkFromALaterVersionIsAFailure() {
        TurboReady(TurboReady.Status.OK, "name", "passphrase", "", 1, kind = 42).toAnswer() shouldBe
            TurboAnswer.Unavailable(TurboUnavailable.FAILED)
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
    fun aProducerWaitingForTurboReceiveLooksForTheCurrentAndThePreviousEpochsLinks() {
        val epoch = DirectLink.EPOCH_MILLIS
        val start = epoch * 1_000 + epoch / 2

        // First, a link started in either epoch may be up already.
        DirectLink.epochsToLookFor(start, lastLooked = null) shouldBe listOf(1_000L, 999L)
        // Then only a link started meanwhile, which has the current epoch's name.
        DirectLink.epochsToLookFor(start + 1_000, lastLooked = 1_000) shouldBe listOf(1_000L)
        // Once that epoch is over, a link started late in it keeps its name, and one started now has the new one.
        DirectLink.epochsToLookFor(epoch * 1_001 + 1, lastLooked = 1_000) shouldBe listOf(1_001L, 1_000L)
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

    @Test
    fun eachAwareTransferGetsAFreshServiceNameAndPassphrase() {
        val all = List(20) { DirectLink.awareCredentials() }

        all.forEach { (name, passphrase) ->
            name shouldMatch Regex("MediaSync-[A-Za-z0-9]{16}")
            passphrase shouldMatch Regex("[A-Za-z0-9]{24}") // Wi-Fi Aware takes 8 to 63 characters.
        }
        all.map { it.first }.toSet() shouldHaveSize 20
        all.map { it.second }.toSet() shouldHaveSize 20
    }

    @Test
    fun hmacSha256MatchesRfc4231TestCase2() {
        "what do ya want for nothing?".encodeUtf8().hmacSha256("Jefe".encodeUtf8()).hex() shouldBe
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    }

    @Test
    fun derivedNetworksMatchTheVectorsOfTheShippedCode() {
        val key = ByteArray(TURBO_KEY_BYTES) { it.toByte() }.toByteString()

        DirectLink.derived(key, epoch = 0) shouldBe ("DIRECT-gW-MediaSync-JqY5TJ" to "6pNsVbdSoBZcxoBT7UBSMebF")
        DirectLink.derived(key, epoch = 1_000) shouldBe ("DIRECT-Up-MediaSync-DAEhLj" to "DzDBKtqfLXD2LYNUjkLpGMAD")
        DirectLink.derived(key, epoch = 123_456_789) shouldBe
            ("DIRECT-aC-MediaSync-UcWhKD" to "UoiNEpdXL2QfyUwNUrYWSNQW")
    }
}
