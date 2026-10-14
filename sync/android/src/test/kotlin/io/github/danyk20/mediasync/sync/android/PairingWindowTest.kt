/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingRequest
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test

class PairingWindowTest {

    private var now = 1_000_000L
    private val window = PairingWindow(clock = { now })

    @Test
    fun tokenWorksOnceWhileTheWindowIsOpen() {
        val token = window.open()

        window.isOpen shouldBe true
        window.redeem(token) shouldBe true
        window.redeem(token) shouldBe false
        window.isOpen shouldBe false
    }

    @Test
    fun tokenExpires() {
        val token = window.open(validForMillis = 1_000)
        now += 1_000

        window.isOpen shouldBe false
        window.redeem(token) shouldBe false
    }

    @Test
    fun wrongTokenIsRejectedAndTheRightOneStillWorks() {
        val token = window.open()

        window.redeem("wrong token 1234".encodeUtf8()) shouldBe false
        window.redeem(token) shouldBe true
    }

    @Test
    fun aNewInvitationReplacesTheOldOne() {
        val old = window.open()
        val new = window.open()

        window.redeem(old) shouldBe false
        window.redeem(new) shouldBe true
    }

    @Test
    fun closingStopsPairing() {
        val token = window.open()
        window.close()

        window.redeem(token) shouldBe false
    }

    @Test
    fun gateNeedsBothTheTokenAndTheUsersApproval() {
        val approvals = PairingApprovals(timeoutMillis = 5_000)
        val gate = pairingGate(window, approvals)
        val token = window.open()
        val executor = Executors.newSingleThreadExecutor()

        val decision = executor.submit<PairingDecision> {
            gate.decide(PairingRequest(deviceId(1), "Anna's Pixel", token))
        }
        while (approvals.pending.value == null) Thread.sleep(5)
        approvals.pending.value?.producerName shouldBe "Anna's Pixel"
        approvals.pending.value?.accept()

        decision.get(5, TimeUnit.SECONDS) shouldBe PairingDecision.ACCEPTED
        approvals.pending.value shouldBe null
        executor.shutdown()
    }

    @Test
    fun gateReportsDeclinedTimedOutAndInvalid() {
        val approvals = PairingApprovals(timeoutMillis = 50)
        val gate = pairingGate(window, approvals)

        gate.decide(PairingRequest(deviceId(1), "x", "no invitation yet".encodeUtf8())) shouldBe
            PairingDecision.INVALID_TOKEN
        gate.decide(PairingRequest(deviceId(1), "x", window.open())) shouldBe PairingDecision.TIMED_OUT

        val executor = Executors.newSingleThreadExecutor()
        val token = window.open()
        val declined = executor.submit<PairingDecision> {
            pairingGate(
                window,
                PairingApprovals(timeoutMillis = 5_000).also { a ->
                    Thread {
                        while (a.pending.value == null) Thread.sleep(5)
                        a.pending.value?.decline()
                    }.start()
                },
            ).decide(PairingRequest(deviceId(1), "x", token))
        }
        declined.get(5, TimeUnit.SECONDS) shouldBe PairingDecision.DECLINED
        executor.shutdown()
    }
}
