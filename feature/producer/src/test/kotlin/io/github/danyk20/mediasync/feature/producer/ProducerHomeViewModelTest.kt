/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.engine.SyncResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProducerHomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val syncState = MutableStateFlow<ProducerState>(ProducerState.Idle())
    private val sync = mockk<ProducerSync>(relaxed = true) {
        every { state } returns syncState
        coEvery { pendingCount() } returns 214
    }
    private val consumer = Peer(DeviceId.of(ByteArray(32)), "Pixel 2 XL", DeviceRole.CONSUMER, 0, null, null)
    private val peers = mockk<PeerStore> { every { observeAll() } returns flowOf(listOf(consumer)) }

    private fun grantLibraryAccess() {
        val library = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        shadowOf(application).grantPermissions(*library, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @Test
    fun withoutAccessItAsksForPhotosFirst() = runTest {
        val viewModel = ProducerHomeViewModel(application, sync, peers)

        val state = viewModel.uiState.first { it.consumerName != null }

        state.canReadLibrary shouldBe false
        state.pendingCount shouldBe null
    }

    @Test
    fun withAccessItCountsWhatsWaiting() = runTest {
        grantLibraryAccess()
        val viewModel = ProducerHomeViewModel(application, sync, peers)

        val state = viewModel.uiState.first { it.pendingCount != null }

        state shouldBe ProducerHomeUiState("Pixel 2 XL", true, true, ProducerState.Idle(), 214)
    }

    @Test
    fun grantingAccessLaterIsPickedUp() = runTest {
        val viewModel = ProducerHomeViewModel(application, sync, peers)
        viewModel.uiState.first { it.consumerName != null }

        grantLibraryAccess()
        viewModel.onPermissionsChanged()

        viewModel.uiState.first { it.canReadLibrary }.canReadLocation shouldBe true
    }

    @Test
    fun buttonsReachTheSyncRuntime() {
        val viewModel = ProducerHomeViewModel(application, sync, peers)

        viewModel.syncNow()
        viewModel.stop()

        verify { sync.syncNow() }
        verify { sync.cancel() }
    }

    @Test
    fun theRouteShowsTheStateAndSyncs() {
        grantLibraryAccess()
        syncState.value = ProducerState.Idle(SyncResult(sent = 3), finishedAtMillis = 0)
        val viewModel = ProducerHomeViewModel(application, sync, peers)

        composeRule.setContent {
            MediaSyncTheme(dynamicColor = false) { ProducerHomeRoute(onPair = {}, viewModel = viewModel) }
        }
        composeRule.onNodeWithText("214 new photos and videos").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").performClick()

        verify { sync.syncNow() }
    }
}
