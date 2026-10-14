/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.SyncFailure
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProducerHomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val paired = ProducerHomeUiState(consumerName = "Pixel 2 XL", canReadLibrary = true, canReadLocation = true)
    private val syncing = paired.copy(
        sync = ProducerState.Syncing(TransferProgress(12, 40, 145_000_000, 830_000_000, "PXL_1.jpg")),
    )
    private val events = mutableListOf<String>()

    @Test
    fun notPaired_offersPairing() {
        show(ProducerHomeUiState())

        composeRule.onNodeWithText("Not paired yet").assertIsDisplayed()
        composeRule.onNodeWithText("Pair with receiving phone").performClick()
        events shouldBe listOf("pair")
    }

    @Test
    fun withoutPhotoAccess_asksForIt() {
        show(paired.copy(canReadLibrary = false))

        composeRule.onNodeWithText("Allow access to photos").assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").performClick()
        events shouldBe listOf("grant")
    }

    @Test
    fun pendingPhotos_canBeSentNow() {
        show(paired.copy(pendingCount = 214))

        composeRule.onNodeWithText("214 new photos and videos").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").performClick()
        events shouldBe listOf("sync")
    }

    @Test
    fun syncing_showsProgressAndCanStop() {
        show(syncing)

        composeRule.onNodeWithText("Sending to Pixel 2 XL").assertIsDisplayed()
        composeRule.onNodeWithText("12 of 40 · 145 MB of 830 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").performClick()
        events shouldBe listOf("stop")
    }

    @Test
    fun failures_explainWhatToDo() {
        show(paired.copy(sync = ProducerState.Failed(SyncFailure.NOT_ALLOWED)))

        composeRule.onNodeWithText("Pixel 2 XL no longer recognises this phone. Pair them again.").assertIsDisplayed()
    }

    @Test
    fun missingLocationAccess_isPointedOut() {
        show(paired.copy(canReadLocation = false, pendingCount = 0))

        composeRule.onNodeWithText("Keep locations").assertIsDisplayed()
    }

    @Test
    fun theChosenFoldersAreShownAndCanBeChanged() {
        show(paired.copy(pendingCount = 0))

        composeRule.onNodeWithText("Camera only").assertIsDisplayed()
        composeRule.onNodeWithText("Folders to send").performClick()
        events shouldBe listOf("folders")
    }

    @Test
    fun handPickedFoldersAreSummarisedByName() {
        val folders =
            setOf("DCIM/Camera/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "Pictures/Instagram/")
        show(paired.copy(pendingCount = 0, sources = SourceSelection.of(folders)))

        composeRule.onNodeWithText("Camera, Instagram and 1 more").assertIsDisplayed()
    }

    @Test
    fun withoutFolders_nothingIsSent() {
        show(paired.copy(pendingCount = 0, sources = SourceSelection.of(emptySet())))

        composeRule.onNodeWithText("No folders chosen").assertIsDisplayed()
        composeRule.onNodeWithText("None chosen").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sync now").fetchSemanticsNodes() shouldBe emptyList()
    }

    @Test
    fun screenshot_notPaired_light() = screenshot(ProducerHomeUiState(), "producer_home_not_paired_light")

    @Test
    fun screenshot_pending_light() = screenshot(paired.copy(pendingCount = 214), "producer_home_pending_light")

    @Test
    fun screenshot_syncing_light() = screenshot(syncing, "producer_home_syncing_light")

    @Test
    fun screenshot_syncing_dark() = screenshot(syncing, "producer_home_syncing_dark", darkTheme = true)

    @Test
    fun screenshot_failed_light() = screenshot(
        paired.copy(sync = ProducerState.Failed(SyncFailure.UNREACHABLE)),
        "producer_home_failed_light",
    )

    @Test
    fun screenshot_noFolders_light() = screenshot(
        paired.copy(pendingCount = 0, sources = SourceSelection.of(emptySet())),
        "producer_home_no_folders_light",
    )

    private fun screenshot(state: ProducerHomeUiState, name: String, darkTheme: Boolean = false) {
        show(state, darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: ProducerHomeUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                ProducerHomeScreen(
                    state = state,
                    onPair = { events += "pair" },
                    onChooseFolders = { events += "folders" },
                    onSyncNow = { events += "sync" },
                    onStop = { events += "stop" },
                    onGrantAccess = { events += "grant" },
                    onOpenSettings = { events += "settings" },
                )
            }
        }
    }
}
