/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.webrtc

import io.kotest.matchers.shouldBe
import java.math.BigInteger
import org.junit.Test
import org.webrtc.RTCStats

/** What counts against the relay allowance (PLAN §8.5), from WebRTC's statistics as libwebrtc reports them. */
class RelayedBytesTest {

    private fun stats(vararg entries: RTCStats) = entries.associateBy { it.id }

    private fun stat(type: String, id: String, vararg members: Pair<String, Any>) =
        RTCStats(0, type, id, members.toMap())

    private fun pair(id: String, local: String, remote: String, vararg extra: Pair<String, Any>) = stat(
        "candidate-pair",
        id,
        "localCandidateId" to local,
        "remoteCandidateId" to remote,
        // uint64 counters arrive as BigInteger.
        "bytesSent" to BigInteger.valueOf(7_000),
        "bytesReceived" to BigInteger.valueOf(300),
        *extra,
    )

    @Test
    fun theSelectedPairThroughTheRelayCounts() {
        val report = stats(
            stat("transport", "T", "selectedCandidatePairId" to "P1"),
            pair("P1", "L1", "R1"),
            stat("local-candidate", "L1", "candidateType" to "relay"),
            stat("remote-candidate", "R1", "candidateType" to "srflx"),
        )

        relayedBytes(report) shouldBe 7_300
    }

    @Test
    fun theOtherPhonesRelayCountsToo() {
        val report = stats(
            stat("transport", "T", "selectedCandidatePairId" to "P1"),
            pair("P1", "L1", "R1"),
            stat("local-candidate", "L1", "candidateType" to "host"),
            stat("remote-candidate", "R1", "candidateType" to "relay"),
        )

        relayedBytes(report) shouldBe 7_300
    }

    @Test
    fun aDirectConnectionDoesnt() {
        val report = stats(
            stat("transport", "T", "selectedCandidatePairId" to "P1"),
            pair("P1", "L1", "R1"),
            pair("P2", "L2", "R1"), // A relay pair that wasn't selected.
            stat("local-candidate", "L1", "candidateType" to "srflx"),
            stat("local-candidate", "L2", "candidateType" to "relay"),
            stat("remote-candidate", "R1", "candidateType" to "srflx"),
        )

        relayedBytes(report) shouldBe 0
    }

    @Test
    fun withoutATransportTheNominatedPairCounts() {
        val report = stats(
            pair("P1", "L1", "R1", "nominated" to true, "state" to "succeeded"),
            stat("local-candidate", "L1", "candidateType" to "relay"),
            stat("remote-candidate", "R1", "candidateType" to "host"),
        )

        relayedBytes(report) shouldBe 7_300
    }

    @Test
    fun theRelayedPairIsTheSelectedOneThroughARelay() {
        val relayed = stats(
            stat("transport", "T", "selectedCandidatePairId" to "P1"),
            pair("P1", "L1", "R1"),
            stat("local-candidate", "L1", "candidateType" to "relay"),
            stat("remote-candidate", "R1", "candidateType" to "srflx"),
        )
        val direct = stats(
            stat("transport", "T", "selectedCandidatePairId" to "P1"),
            pair("P1", "L1", "R1"),
            stat("local-candidate", "L1", "candidateType" to "host"),
            stat("remote-candidate", "R1", "candidateType" to "prflx"),
        )

        relayedPair(relayed)?.id shouldBe "P1"
        relayedPair(direct) shouldBe null
    }

    @Test
    fun beforeAnyPairIsChosenNothingCounts() {
        relayedBytes(stats(stat("transport", "T"))) shouldBe 0
    }
}
