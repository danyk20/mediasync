/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Of several connections' files, one shows at a time (docs/PLAN.md §7, *Per-file progress*). */
class SendingFollowerTest {

    private val follower = SendingFollower()

    private fun file(key: String, sent: Long, started: Long, size: Long = 100) =
        SendingItem(key, "$key.jpg", size, video = false, bytesSent = sent, startedNanos = started)

    @Test
    fun nothingSendingShowsNothing() {
        follower.follow(listOf(null, null)).shouldBeNull()
    }

    @Test
    fun theOldestFileStillGoingShowsUntilItsDone() {
        follower.follow(listOf(file("b", 10, started = 2), file("a", 10, started = 1))) shouldBe file("a", 10, 1)

        // A newer one moving faster doesn't take over.
        follower.follow(listOf(file("b", 90, started = 2), file("a", 20, started = 1))) shouldBe file("a", 20, 1)

        // Done: shown done once, then the oldest one still going.
        follower.follow(listOf(file("b", 95, started = 2), file("a", 100, started = 1))) shouldBe file("a", 100, 1)
        follower.follow(listOf(file("b", 96, started = 2), file("a", 100, started = 1))) shouldBe file("b", 96, 2)
    }

    @Test
    fun aConnectionMovingOnToAnotherFileEndsTheOneFollowed() {
        follower.follow(listOf(file("a", 10, started = 1), file("b", 10, started = 2)))

        follower.follow(listOf(file("c", 0, started = 3), file("b", 20, started = 2))) shouldBe file("b", 20, 2)
    }

    @Test
    fun withNothingElseGoingTheLastFileStaysShownDone() {
        follower.follow(listOf(file("a", 50, started = 1)))
        follower.follow(listOf(file("a", 100, started = 1))) shouldBe file("a", 100, 1)

        follower.follow(listOf(file("a", 100, started = 1), null)) shouldBe file("a", 100, 1)
        follower.follow(listOf(null)) shouldBe file("a", 100, 1)
    }
}
