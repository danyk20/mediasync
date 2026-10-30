/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.LeftOutStore
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LeftOutItemsTest {

    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MediaSyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val source = mockk<MediaSource>(relaxed = true)
    private val leftOut = LeftOutItems(LeftOutStore(database.leftOutItemDao()), source)

    private val beach = FakeMedia("DCIM/Camera/beach", byteArrayOf(1, 2, 3)).item
    private val beachCopy = FakeMedia("Pictures/Edited/beach", byteArrayOf(1, 2, 3)).item
    private val dog = FakeMedia("DCIM/Camera/dog", byteArrayOf(4, 5, 6)).item

    @After
    fun tearDown() = database.close()

    @Test
    fun aPhotoLeftOutIsLeftOutWhereverItsCopiesAre() = runBlocking<Unit> {
        leftOut.leaveOut(listOf(beach))

        leftOut.of(listOf(beach, beachCopy, dog)) shouldBe listOf(beach, beachCopy)
        leftOut.fingerprints.first() shouldBe setOf(beach.descriptor.fingerprint)

        leftOut.bringBack(listOf(beachCopy))

        leftOut.of(listOf(beach, beachCopy, dog)).shouldBeEmpty()
    }

    @Test
    fun whatTheUserSharesIsSentEvenIfLeftOutBefore() = runBlocking<Unit> {
        leftOut.leaveOut(listOf(beach))
        val shared = beach.copy(key = "content://media/external/images/media/7", shared = true)

        leftOut.of(listOf(shared)).shouldBeEmpty()
    }

    @Test
    fun aSharedItemLeftOutLeavesTheQueueAndIsntSentByTheSyncHoldingIt() = runBlocking<Unit> {
        val shared = dog.copy(shared = true)

        leftOut.leaveOut(listOf(shared))

        verify { source.withdrawn(shared) }
        leftOut.of(listOf(shared, dog)) shouldBe listOf(shared, dog)
    }
}
