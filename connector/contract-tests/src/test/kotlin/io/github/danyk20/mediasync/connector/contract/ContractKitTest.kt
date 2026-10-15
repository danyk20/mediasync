/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.contract

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.stream.Stream
import org.junit.jupiter.api.Test as JupiterTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.runner.JUnitCore
import org.junit.runner.Result

/** The kit itself: well-behaved connectors pass it, and each kind of misbehaviour fails the check meant for it. */
class ContractKitTest {

    @JupiterTest
    fun aWellBehavedUploadingConnectorPassesAndSkipsTheChecksForOtherApps() {
        val result = run(SampleCloudContract::class.java)

        result.failures.map { "${it.description.methodName}: ${it.message}" }.shouldBeEmpty()
        result.assumptionFailureCount shouldBe 2
    }

    @JupiterTest
    fun aWellBehavedConnectorForAnotherAppPassesAndSkipsTheChecksForUploads() {
        val result = run(SampleAppContract::class.java)

        result.failures.map { "${it.description.methodName}: ${it.message}" }.shouldBeEmpty()
        result.assumptionFailureCount shouldBe 4
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misbehaving")
    fun eachMisbehaviourIsCaughtByItsCheck(kit: Class<*>, expected: Set<String>) {
        run(kit).failures.map { it.description.methodName }.toSet() shouldBe expected
    }

    private fun run(kit: Class<*>): Result = JUnitCore().run(kit)

    private companion object {
        @JvmStatic
        fun misbehaving(): Stream<Arguments> = Stream.of(
            Arguments.of(DuplicatingCloudContract::class.java, setOf("backingUpTwiceKeepsOneCopy")),
            Arguments.of(
                AlteringCloudContract::class.java,
                setOf("aPhotoReachesTheDestinationByteForByte", "aVideoReachesTheDestinationByteForByte"),
            ),
            Arguments.of(ThrowingCloudContract::class.java, setOf("aRateLimitIsReportedForRetrying")),
            Arguments.of(OverclaimingCloudContract::class.java, setOf("checksFindCopiesThatWentMissing")),
            Arguments.of(MismatchedCloudContract::class.java, setOf("theDescriptorsAgree")),
            Arguments.of(CarelessAppContract::class.java, setOf("anotherAppsConnectorSkipsWhatIsntOnThePhone")),
            Arguments.of(ClaimingAppContract::class.java, setOf("checksFindCopiesThatWentMissing")),
            Arguments.of(
                TrashCountingAppContract::class.java,
                setOf("anotherAppsConnectorCountsOnlyWhatTheAppRemoved"),
            ),
        )
    }
}
