/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PhotoDetailsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettingsRepository()
    private val consumer = Peer(DeviceId.of(ByteArray(32)), "Pixel 2 XL", DeviceRole.CONSUMER, 0, null, null)
    private val peers = mockk<PeerStore> { every { observeConsumer() } returns flowOf(consumer) }
    private val chosen = mutableListOf<PhotoDetails>()

    @Test
    fun theChoiceIsStoredAndShown() = runTest {
        val viewModel = PhotoDetailsViewModel(settings, peers)
        viewModel.uiState.first { it.consumerName != null } shouldBe
            PhotoDetailsUiState(PhotoDetails.KEEP_ALL, "Pixel 2 XL")

        viewModel.choose(PhotoDetails.REMOVE_LOCATION)

        settings.photoDetails.first() shouldBe PhotoDetails.REMOVE_LOCATION
        viewModel.uiState.first { it.details == PhotoDetails.REMOVE_LOCATION }
    }

    @Test
    fun everyChoiceIsExplained() {
        show(PhotoDetailsUiState(PhotoDetails.KEEP_ALL, "Pixel 2 XL"))

        composeRule.onNodeWithText("Keep all").assertIsSelected()
        composeRule.onNodeWithText(
            "Places are removed: GPS, place names and video location. Everything else stays.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Remove all details").performClick()
        chosen shouldBe listOf(PhotoDetails.REMOVE_ALL)
        composeRule.onAllNodesWithText("RAW photos", substring = true).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun removeAllSaysWhatItMeansForMotionPhotosAndRaw() {
        show(PhotoDetailsUiState(PhotoDetails.REMOVE_ALL, "Pixel 2 XL"))

        composeRule.onNodeWithText("RAW photos aren't sent", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Motion Photos and Ultra HDR photos", substring = true).assertIsDisplayed()
    }

    @Test
    fun screenshot_removeLocation_light() {
        show(PhotoDetailsUiState(PhotoDetails.REMOVE_LOCATION, "Pixel 2 XL"))
        composeRule.onRoot().captureScreenshot("producer_photo_details_light")
    }

    @Test
    fun screenshot_removeAll_dark() {
        show(PhotoDetailsUiState(PhotoDetails.REMOVE_ALL, "Pixel 2 XL"), darkTheme = true)
        composeRule.onRoot().captureScreenshot("producer_photo_details_remove_all_dark")
    }

    private fun show(state: PhotoDetailsUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                PhotoDetailsScreen(state = state, onChoose = { chosen += it }, onDone = {})
            }
        }
    }
}
