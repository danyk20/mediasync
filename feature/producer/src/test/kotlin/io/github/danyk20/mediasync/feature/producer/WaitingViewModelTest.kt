/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.LeftOutStore
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.LeftOutItems
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.github.danyk20.mediasync.sync.engine.SourceItem
import io.github.danyk20.mediasync.sync.engine.testing.FakeMedia
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WaitingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MediaSyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val leftOut = LeftOutItems(LeftOutStore(database.leftOutItemDao()), mockk(relaxed = true))

    private val beach = media("beach", "2026-10-28T10:00:00Z")
    private val dog = media("dog", "2026-10-29T10:00:00Z", "video/mp4")
    private val cake = media("cake", "2026-10-27T10:00:00Z")
    private var waiting = listOf(beach, dog, cake)
    private val syncState = MutableStateFlow<ProducerState>(ProducerState.Idle())
    private val sync = mockk<ProducerSync> {
        every { state } returns syncState
        coEvery { waitingItems() } answers { waiting }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun showsWhatTheNextSyncSendsNewestFirst() = runTest {
        val state = shown(viewModel()) { it.toSync.size == 3 }

        state.toSync.map { it.key } shouldBe listOf("dog", "beach", "cake")
        state.toSync.map { it.video } shouldBe listOf(true, false, false)
        state.leftOut.shouldBeEmpty()
    }

    @Test
    fun theChosenOnesAreLeftOutAndThatCanBeUndone() = runTest {
        val viewModel = viewModel()
        shown(viewModel) { it.toSync.size == 3 }

        viewModel.toggle("dog")
        viewModel.toggle("cake")
        shown(viewModel) { it.selected == setOf("dog", "cake") }
        viewModel.moveSelected(WaitingList.TO_SYNC)

        val after = shown(viewModel) { it.leftOut.size == 2 }
        after.toSync.map { it.key } shouldBe listOf("beach")
        after.leftOut.map { it.key } shouldBe listOf("dog", "cake")
        after.selected.shouldBeEmpty()
        viewModel.changes.first() shouldBe WaitingChange(leftOut = true, count = 2)
        leftOut.of(waiting) shouldBe listOf(dog, cake) // Saved: the next sync leaves them out.

        viewModel.undo()

        shown(viewModel) { it.leftOut.isEmpty() }.toSync.size shouldBe 3
    }

    @Test
    fun whatWasLeftOutComesBack() = runTest {
        leftOut.leaveOut(listOf(beach))
        val viewModel = viewModel()
        shown(viewModel) { it.leftOut.size == 1 }

        viewModel.toggle("beach")
        viewModel.moveSelected(WaitingList.LEFT_OUT)

        shown(viewModel) { it.leftOut.isEmpty() }.toSync.map { it.key } shouldBe listOf("dog", "beach", "cake")
        viewModel.changes.first() shouldBe WaitingChange(leftOut = false, count = 1)
    }

    @Test
    fun aWholeDayIsChosenAtOnceAndTheChoiceCleared() = runTest {
        val viewModel = viewModel()
        shown(viewModel) { it.toSync.size == 3 }

        viewModel.select(listOf("dog", "beach"), chosen = true)
        shown(viewModel) { it.selected == setOf("dog", "beach") }
        viewModel.select(listOf("beach"), chosen = false)
        shown(viewModel) { it.selected == setOf("dog") }
        viewModel.clearSelection()

        shown(viewModel) { it.selected.isEmpty() }
    }

    @Test
    fun whenASyncEndsWhatItSentIsNoLongerShown() = runTest {
        val viewModel = viewModel()
        shown(viewModel) { it.toSync.size == 3 }

        syncState.value = ProducerState.Syncing(progress = null)
        waiting = listOf(beach)
        syncState.value = ProducerState.Idle()

        shown(viewModel) { it.toSync.size == 1 }
    }

    private fun TestScope.viewModel() = WaitingViewModel(sync, leftOut, FakeMediaLibrary()).also { viewModel ->
        backgroundScope.launch { viewModel.uiState.collect {} } // As the screen does, while it's shown.
    }

    private suspend fun shown(
        viewModel: WaitingViewModel,
        until: (WaitingUiState.Ready) -> Boolean,
    ): WaitingUiState.Ready = viewModel.uiState.first {
        it is WaitingUiState.Ready && until(it)
    } as WaitingUiState.Ready

    private fun media(name: String, takenAt: String, mimeType: String = "image/jpeg"): SourceItem = FakeMedia(
        name,
        name.encodeToByteArray(),
        mimeType = mimeType,
        meta = MediaMeta(capturedAtMillis = Instant.parse(takenAt).toEpochMilli()),
    ).item
}
