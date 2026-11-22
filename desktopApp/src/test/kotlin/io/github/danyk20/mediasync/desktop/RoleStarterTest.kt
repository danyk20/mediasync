/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.platform.desktop.IdentityLockedException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RoleStarterTest {

    @Test
    fun `waits while the keychain is locked and starts once it opens, exactly once`() = runTest {
        var attempts = 0
        var started = 0
        val pauses = mutableListOf<Long>()
        val starter = RoleStarter(
            start = {
                attempts++
                if (attempts <= 2) throw IdentityLockedException("locked")
                started++
            },
            pause = { pauses += it },
        )

        starter.run()

        attempts shouldBe 3
        started shouldBe 1
        pauses shouldContainExactly listOf(10_000L, 10_000L)
        starter.state.value shouldBe StartState.RUNNING
    }

    @Test
    fun `shows the waiting state while it retries, and logs it once`() = runTest {
        val states = mutableListOf<StartState>()
        val logs = mutableListOf<String>()
        var attempts = 0
        lateinit var starter: RoleStarter
        starter = RoleStarter(
            start = {
                attempts++
                if (attempts <= 3) throw IdentityLockedException("locked")
            },
            log = { logs += it },
            pause = { states += starter.state.value },
        )

        starter.run()

        states shouldContainExactly List(3) { StartState.WAITING_FOR_KEYCHAIN }
        logs.size shouldBe 1
    }

    @Test
    fun `another failure is logged and not retried`() = runTest {
        var attempts = 0
        val logs = mutableListOf<String>()
        val starter = RoleStarter(
            start = {
                attempts++
                error("port in use")
            },
            log = { logs += it },
            pause = { error("must not wait") },
        )

        starter.run()

        attempts shouldBe 1
        starter.state.value shouldBe StartState.FAILED
        logs.single().contains("port in use") shouldBe true
    }
}
