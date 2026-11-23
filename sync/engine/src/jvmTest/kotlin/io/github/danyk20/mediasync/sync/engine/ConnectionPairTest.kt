/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.sync.engine.testing.ConnectionPair
import io.github.danyk20.mediasync.sync.engine.testing.deviceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import org.junit.jupiter.api.Test

/** The in-memory connection ends as a socket does, so sessions see the same failures as over Wi-Fi. */
class ConnectionPairTest {

    private val connection = ConnectionPair(deviceId(1), deviceId(2))

    @Test
    fun aFlushDoesntFailBecauseTheOtherSideClosedAfterReading() {
        connection.consumerEnd.sink.writeUtf8("bye").emit() // Sent, not yet flushed.
        connection.producerEnd.source.readUtf8(3) shouldBe "bye"
        connection.producerEnd.close()

        connection.consumerEnd.sink.flush()
    }

    @Test
    fun aWriteAfterTheOtherSideClosedFails() {
        connection.producerEnd.close()

        shouldThrow<IOException> { connection.consumerEnd.sink.writeUtf8("more").flush() }
    }

    @Test
    fun usingAnEndAfterClosingItFailsWithAnIoException() {
        connection.producerEnd.close()

        shouldThrow<IOException> { connection.producerEnd.sink.writeUtf8("late").flush() }
    }
}
