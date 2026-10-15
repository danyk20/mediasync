/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.connector.testing.account
import io.github.danyk20.mediasync.connector.testing.backupItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DescriptorTest {

    @Test
    fun idsAreShortLowercaseNames() {
        ConnectorId("google-photos-app").toString() shouldBe "google-photos-app"
        listOf("", "x", "Google", "google photos", "-photos", "a".repeat(41)).forEach { id ->
            shouldThrow<IllegalArgumentException> { ConnectorId(id) }
        }
    }

    @Test
    fun contentIdsAreSha256InLowercaseHex() {
        val hex = "ab".repeat(32)
        ContentId(hex).toString() shouldBe hex
        listOf("ab", "AB".repeat(32), "zz".repeat(32)).forEach { hex ->
            shouldThrow<IllegalArgumentException> { ContentId(hex) }
        }
        shouldThrow<IllegalArgumentException> { AccountId(" ") }
        AccountId("google-photos-app").toString() shouldBe "google-photos-app"
    }

    @Test
    fun limitsTellWhatFits() {
        val limits = ConnectorLimits(maxPhotoBytes = 200, maxVideoBytes = 20_000)

        limits.allows(200, video = false).shouldBeTrue()
        limits.allows(201, video = false).shouldBeFalse()
        limits.allows(201, video = true).shouldBeTrue()
        ConnectorLimits.None.allows(Long.MAX_VALUE, video = true).shouldBeTrue()
        listOf(
            { ConnectorLimits(maxPhotoBytes = 0) },
            { ConnectorLimits(maxVideoBytes = -1) },
            { ConnectorLimits(batchSize = 0) },
            { ConnectorLimits(requestsPerSecond = 0.0) },
            { ConnectorLimits(requestsPerDay = 0) },
            { ConnectorLimits(parallelism = 0) },
        ).forEach { shouldThrow<IllegalArgumentException> { it() } }
    }

    @Test
    fun anotherAppsConnectorNeitherChecksNorSignsIn() {
        val delegated = setOf(Capability.DELEGATED_TO_DEVICE_APP)
        val descriptor = ConnectorDescriptor(ConnectorId("photos-app"), "Photos", delegated)

        descriptor.delegated.shouldBeTrue()
        (Capability.DELEGATED_TO_DEVICE_APP in descriptor).shouldBeTrue()
        (Capability.VERIFY in descriptor).shouldBeFalse()
        shouldThrow<IllegalArgumentException> {
            ConnectorDescriptor(ConnectorId("photos-app"), "Photos", delegated + Capability.VERIFY)
        }
        shouldThrow<IllegalArgumentException> {
            ConnectorDescriptor(ConnectorId("photos-app"), "Photos", delegated + Capability.HASH_LOOKUP)
        }
        shouldThrow<IllegalArgumentException> {
            ConnectorDescriptor(ConnectorId("photos-app"), "Photos", delegated, authKind = AuthKind.OAUTH2)
        }
    }

    @Test
    fun itemsKnowWhetherTheyAreVideos() {
        backupItem(1).video.shouldBeFalse()
        backupItem(2, mimeType = "video/mp4").video.shouldBeTrue()
        backupItem(3, bytes = byteArrayOf(1, 2, 3)).content.open().use { it.readBytes() } shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun withoutSignInThereIsNothingToDo() = runTest {
        ConnectorAuthenticator.None.kind shouldBe AuthKind.NONE
        ConnectorAuthenticator.None.state(account()) shouldBe AuthState.NotNeeded
        ConnectorAuthenticator.None.signOut(account())
        ProgressSink.None.onProgress(1, 2)
    }
}
