/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.feature.consumer.ConsumerHomeViewModel
import io.github.danyk20.mediasync.feature.pairing.ConsumerPairingViewModel
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The graph builds with every binding the receiving screens need, on files in a temporary folder. */
class DesktopGraphTest {

    @TempDir
    lateinit var directory: Path

    private val graphs = TestGraphs()

    @AfterEach
    fun shutDownGraphs() = graphs.shutDown()

    private fun graph() = graphs.create(directory)

    @Test
    fun knowsTheReceivingScreensViewModels() {
        val models = graph().viewModelProviders.keys

        models shouldContain ConsumerHomeViewModel::class
        models shouldContain ConsumerPairingViewModel::class
    }

    @Test
    fun makesItsIdentityOnceAndKeepsItBetweenRuns() {
        val first = graph().identity.identity().id

        Files.isRegularFile(directory.resolve("identity").resolve("password")) shouldBe true
        graph().identity.identity().id shouldBe first
    }

    @Test
    fun startsNotListening() {
        graph().receiver.state.value.listening shouldBe false
    }
}
