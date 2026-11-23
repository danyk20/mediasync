/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.util.ioDispatcher
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class ProducerSyncTest {

    private val consumerId: DeviceId = deviceId(1)

    private fun scope() = CoroutineScope(SupervisorJob() + ioDispatcher)

    private fun consumer(): Peer = Peer(
        id = consumerId,
        name = "Receiver",
        role = DeviceRole.CONSUMER,
        pairedAtMillis = 0,
        lastAddress = null,
        lastSeenMillis = null,
    )

    @Test
    fun syncingWithoutAPairedReceiverFails() {
        val scope = scope()
        runTest {
            val sync = producerSync(FakePeers(), FakeLanLinks(ArrayDeque()), scope)

            sync.syncAndWait() shouldBe ProducerState.Failed(SyncFailure.NOT_PAIRED)
        }
        scope.cancel()
    }

    @Test
    fun aReceiverThatCannotBeReachedIsUnreachable() {
        val scope = scope()
        runTest {
            // Paired, but no address is known and nothing is found on the network: no connection at all.
            val sync = producerSync(FakePeers(consumer()), FakeLanLinks(ArrayDeque()), scope)

            sync.syncAndWait() shouldBe ProducerState.Failed(SyncFailure.UNREACHABLE)
        }
        scope.cancel()
    }
}
