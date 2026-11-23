/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class LeftOutItemsTest {

    private val photo = FakeMedia("photo", byteArrayOf(1, 2, 3))
    private val shared = FakeMedia("shared", byteArrayOf(4, 5, 6), shared = true)

    private fun leftOut(store: FakeLeftOutList = FakeLeftOutList()) = LeftOutItems(store, FakeMediaSource())

    @Test
    fun leftOutFingerprintsHideMatchingItems() {
        runTest {
            val items = leftOut()
            items.leaveOut(listOf(photo.item))

            // The shared one isn't left out: it was never withdrawn.
            items.of(listOf(photo.item, shared.item)) shouldBe listOf(photo.item)
        }
    }

    @Test
    fun aSharedItemIsOnlyLeftOutAfterItWasWithdrawn() {
        runTest {
            val store = FakeLeftOutList()
            val items = leftOut(store)
            // Its fingerprint is stored, but it was shared: a sync holding it as shared still sends it.
            store.add(listOf(shared.descriptor.fingerprint))
            items.of(listOf(shared.item)).shouldBeEmpty()

            items.leaveOut(listOf(shared.item))

            items.of(listOf(shared.item)) shouldBe listOf(shared.item)
        }
    }

    @Test
    fun bringingBackShowsItAgain() {
        runTest {
            val items = leftOut()
            items.leaveOut(listOf(photo.item))
            items.bringBack(listOf(photo.item))

            items.of(listOf(photo.item)).shouldBeEmpty()
        }
    }
}
