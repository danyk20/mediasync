/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The coroutine pieces [ParallelSync] is built from: its probe waits and how it hands back a stream's outcome. */
class ParallelSyncPrimitivesTest {

    @Test
    fun aProbeElapsesWhileAStreamIsStillRunning(): Unit = runBlocking {
        val neverEnds = CompletableDeferred<Result<Int>>()

        val elapsed = withTimeoutOrNull(PROBE_MILLIS) { listOf<Job>(neverEnds).joinAll() } == null

        elapsed shouldBe true
    }

    @Test
    fun aProbeEndsEarlyWhenEveryStreamHasEnded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val streams = List(2) { scope.async { Result.success(it) } }

            val elapsed = withTimeoutOrNull(STREAM_TIMEOUT_MILLIS) { streams.joinAll() } == null

            elapsed shouldBe false
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailedStreamKeepsTheSameExceptionAndItsByeReason(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val original = PeerEndedSessionException(BYE_REASON)
        try {
            // As ParallelSync runs each stream: the failure travels inside the Result, not as a thrown exception.
            val stream = scope.async { runCatching<Int> { throw original } }

            val failure = stream.await().exceptionOrNull()

            assertSame(original, failure)
            assertIs<PeerEndedSessionException>(failure).byeReason shouldBe BYE_REASON
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val PROBE_MILLIS = 20L
        const val STREAM_TIMEOUT_MILLIS = 5_000L
        const val BYE_REASON = 3
    }
}
