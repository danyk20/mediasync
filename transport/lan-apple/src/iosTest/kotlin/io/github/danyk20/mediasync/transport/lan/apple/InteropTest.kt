/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.protocol.Bye
import io.github.danyk20.mediasync.sync.protocol.FrameCodec
import io.github.danyk20.mediasync.sync.protocol.Hello
import io.github.danyk20.mediasync.sync.protocol.MessageCodec
import io.github.danyk20.mediasync.sync.protocol.PROTOCOL_VERSION
import io.github.danyk20.mediasync.sync.protocol.WireRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException

/**
 * Talks to the JVM InteropServer (transport/lan-testing) that Gradle starts for this test: Apple's TLS client against
 * the JDK's TLS server, with mutual authentication and the pin, then one framed Hello each way.
 */
class InteropTest {
    private val timeouts = AppleLanTimeouts()
    private val failureBudget = (timeouts.connectMillis + timeouts.handshakeMillis + BUDGET_SLACK_MILLIS).milliseconds

    @Test
    fun helloIsExchangedOverMutualTlsWithThePinnedServer() {
        val dir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(dir)
        val identity = AppleIdentityFactory.create()

        val connection = NwLanClient.connect("127.0.0.1", server.port, identity, server.pin, timeouts)
        try {
            assertEquals(server.pin, connection.peerId)
            FrameCodec.write(
                MessageCodec.encode(Hello(PROTOCOL_VERSION, "ios-interop", WireRole.PRODUCER)),
                connection.sink,
            )
            connection.sink.flush()

            val reply = assertIs<Hello>(MessageCodec.decode(assertNotNullFrame(FrameCodec.read(connection.source))))
            assertEquals(PROTOCOL_VERSION, reply.protocolVersion)
            assertEquals(WireRole.CONSUMER, reply.role)

            FrameCodec.write(MessageCodec.encode(Bye()), connection.sink)
            connection.sink.flush()
        } finally {
            connection.close()
        }
        recordPass("helloIsExchangedOverMutualTlsWithThePinnedServer")
    }

    @Test
    fun aWrongPinFailsTheHandshakeInTime() {
        val dir = interopDirectory() ?: run {
            println(SKIP_REASON)
            return
        }
        val server = readInteropServer(dir)
        val identity = AppleIdentityFactory.create()
        val first = server.pin.hex.first()
        val wrongPin = DeviceId((if (first == '0') '1' else '0').toString() + server.pin.hex.drop(1))

        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<IOException> {
            NwLanClient.connect("127.0.0.1", server.port, identity, wrongPin, timeouts)
        }
        assertTrue(started.elapsedNow() < failureBudget, "The pin mismatch took ${started.elapsedNow()}")
        recordPass("aWrongPinFailsTheHandshakeInTime")
    }

    @Test
    fun aRefusedPortFailsInsteadOfWaiting() {
        val identity = AppleIdentityFactory.create()
        val expected = DeviceId("0".repeat(64))

        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<IOException> {
            NwLanClient.connect("127.0.0.1", REFUSED_PORT, identity, expected, timeouts)
        }
        assertTrue(started.elapsedNow() < failureBudget, "The refused connection took ${started.elapsedNow()}")
        recordPass("aRefusedPortFailsInsteadOfWaiting")
    }

    private fun assertNotNullFrame(frame: io.github.danyk20.mediasync.sync.protocol.Frame?) =
        checkNotNull(frame) { "The server closed the connection before its Hello" }

    private companion object {
        /** Port 1 is never open on a Mac: the connection is refused at once. */
        const val REFUSED_PORT = 1
        const val BUDGET_SLACK_MILLIS = 5_000L
    }
}
