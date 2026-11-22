/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.createGraphFactory
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.platform.desktop.DesktopIdentityProvider
import io.github.danyk20.mediasync.platform.desktop.FileSecretStore
import io.github.danyk20.mediasync.sync.android.DeviceConditions
import io.github.danyk20.mediasync.sync.android.PauseReason
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The sending part, as the app runs it: real graphs and real identity in a temporary folder, and fakes at the sync. */
class SendingPartTest {

    @TempDir
    lateinit var directory: Path

    private val graphs = TestGraphs()

    @AfterEach
    fun shutDown() = graphs.shutDown()

    @Test
    fun `the sending role starts the scheduler only once the identity can be read`() = runBlocking<Unit> {
        // A first start creates the identity and its password; then the password goes missing: the keychain is locked.
        val first = createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))
        first.identity.identity()
        AppShutdown(first.shutdownSteps()).run()
        val password = directory.resolve("identity").resolve("password")
        val saved = Files.readAllBytes(password)
        Files.delete(password)

        val graph = graphs.create(directory)
        graph.settings.setRole(DeviceRole.PRODUCER)
        val roles = launch { graph.roles.run(this) }
        try {
            withTimeout(10.seconds) { graph.sending.state.first { it == StartState.WAITING_FOR_KEYCHAIN } }
            graph.senderScheduler.active.value shouldBe false

            Files.write(password, saved) // Unlocked: the next try (every 10 s) reads the identity.
            withTimeout(30.seconds) { graph.senderScheduler.active.first { it } }
            graph.sending.state.value shouldBe StartState.RUNNING
        } finally {
            graph.roles.stopAll()
            roles.cancel()
        }
    }

    @Test
    fun `the receiving role never starts the scheduler`() = runBlocking<Unit> {
        val graph = graphs.create(directory)
        graph.settings.setRole(DeviceRole.CONSUMER)
        val roles = launch { graph.roles.run(this) }
        try {
            withTimeout(10.seconds) { graph.receiving.state.first { it == StartState.RUNNING } }

            graph.senderScheduler.active.value shouldBe false
            graph.sending.state.value.shouldBeNull()
        } finally {
            graph.roles.stopAll()
            roles.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stop gives up after five seconds on a sync that ignores cancellation, and logs it`() = runTest {
        val logs = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        var cancels = 0
        val scheduler = SenderScheduler(
            consumerSchedule = flowOf(SyncSchedule.WhileCharging),
            folderChanges = emptyFlow(),
            lastSyncMillis = { null },
            lastHandledMillis = { null },
            saveHandledMillis = {},
            conditions = object : DeviceConditions {
                override fun pauseReason(): PauseReason? = null

                override fun pluggedIn(): Boolean = true
            },
            sync = {
                started.complete(Unit)
                withContext(NonCancellable) { delay(1.hours) } // Ignores the cancellation for an hour.
                ProducerState.Idle(SyncResult())
            },
            log = { logs += it },
        )
        val part = SendingPart(
            cancelSync = { cancels++ },
            identity = DesktopIdentityProvider(directory, FileSecretStore(directory.resolve("password"))),
            scheduler = scheduler,
            log = { logs += it },
        )
        part.start(backgroundScope)
        started.await()

        part.stop()

        testScheduler.currentTime shouldBe 5_000L
        cancels shouldBe 1
        logs.any { "did not stop" in it } shouldBe true
    }
}
