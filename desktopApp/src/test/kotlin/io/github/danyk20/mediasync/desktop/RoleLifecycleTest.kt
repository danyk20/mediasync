/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The part of the stored role runs, and only that one, on fakes. */
class RoleLifecycleTest {

    private val events = mutableListOf<String>()

    private inner class FakePart(private val name: String) : RolePart {
        override fun start(scope: CoroutineScope) {
            events += "start $name"
        }

        override suspend fun stop() {
            events += "stop $name"
        }
    }

    private val logs = mutableListOf<String>()
    private val parts = mapOf(DeviceRole.CONSUMER to FakePart("receiving"), DeviceRole.PRODUCER to FakePart("sending"))

    private fun lifecycle(vararg roles: DeviceRole?) = RoleLifecycle(flowOf(*roles), parts, logs::add)

    private fun scope() = CoroutineScope(kotlinx.coroutines.Job())

    @Test
    fun `nothing runs while no role is chosen`() = runBlocking<Unit> {
        val lifecycle = lifecycle(null)

        lifecycle.run(scope())

        events shouldContainExactly emptyList()
        lifecycle.active shouldBe null
    }

    @Test
    fun `a receiving computer starts the receiving part only`() = runBlocking<Unit> {
        val lifecycle = lifecycle(DeviceRole.CONSUMER)

        lifecycle.run(scope())

        events shouldContainExactly listOf("start receiving")
        lifecycle.active shouldBe DeviceRole.CONSUMER
    }

    @Test
    fun `a sending computer starts the sending part only`() = runBlocking<Unit> {
        val lifecycle = lifecycle(DeviceRole.PRODUCER)

        lifecycle.run(scope())

        events shouldContainExactly listOf("start sending")
        lifecycle.active shouldBe DeviceRole.PRODUCER
    }

    @Test
    fun `choosing the role after the first start starts its part then`() = runBlocking<Unit> {
        lifecycle(null, DeviceRole.PRODUCER).run(scope())

        events shouldContainExactly listOf("start sending")
    }

    @Test
    fun `clearing the role stops the part, and it is not started again in the same run`() = runBlocking<Unit> {
        val lifecycle = lifecycle(DeviceRole.CONSUMER, null, DeviceRole.CONSUMER)

        lifecycle.run(scope())

        events shouldContainExactly listOf("start receiving", "stop receiving")
        logs.size shouldBe 1
        lifecycle.active shouldBe null
    }

    @Test
    fun `quitting stops the running part and is safe twice`() = runBlocking<Unit> {
        val lifecycle = lifecycle(DeviceRole.PRODUCER)
        lifecycle.run(scope())

        lifecycle.stopAll()
        lifecycle.stopAll()

        events shouldContainExactly listOf("start sending", "stop sending")
    }

    @Test
    fun `quitting before any role was chosen does nothing, and nothing starts afterwards`() = runBlocking<Unit> {
        val lifecycle = lifecycle(DeviceRole.CONSUMER)

        lifecycle.stopAll()
        lifecycle.run(scope())

        events shouldContainExactly emptyList()
    }

    @Test
    fun `shutdown runs every step in order, whichever role runs`() = runBlocking<Unit> {
        val lifecycle = lifecycle(DeviceRole.PRODUCER)
        lifecycle.run(scope())
        val steps = shutdownSteps(
            lifecycle,
            cancelScope = { events += "cancel scope" },
            closeDatabase = { events += "close database" },
        )

        AppShutdown(steps).run()

        events shouldContainExactly listOf("start sending", "stop sending", "cancel scope", "close database")
    }
}
