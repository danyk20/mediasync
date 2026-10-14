/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.MediaFolder
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.testing.captureScreenshot
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
class SourceFoldersScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val folders = listOf(
        FolderChoice(MediaFolder("DCIM/Camera/", 2_814, 9_400_000_000, null), included = true),
        FolderChoice(
            MediaFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 3_020, 1_200_000_000, null),
            included = false,
        ),
        FolderChoice(MediaFolder("Pictures/Screenshots/", 412, 310_000_000, null), included = false),
        FolderChoice(MediaFolder("Pictures/Instagram/", 1, 2_400_000, null), included = false),
    )
    private val cameraOnly = SourceFoldersUiState.Ready(SourceSelection.Default, folders)
    private val events = mutableListOf<String>()

    @Test
    fun eachFolderShowsWhatItHoldsAndWhetherItsSent() {
        show(cameraOnly)

        composeRule.onNodeWithText("2,814 items · 9.4 GB · DCIM/Camera").assertIsDisplayed()
        composeRule.onNodeWithText("1 item · 2.4 MB · Pictures/Instagram").assertIsDisplayed()
        composeRule.onNodeWithText("Camera").assertIsOn()
        composeRule.onNodeWithText("Screenshots").assertIsOff()
        composeRule.onNodeWithText("Camera only").assertIsSelected()
    }

    @Test
    fun tappingAFolderTurnsItOnOrOff() {
        show(cameraOnly)

        composeRule.onNodeWithText("WhatsApp Images").performClick()

        events shouldBe listOf("toggle Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/")
    }

    @Test
    fun presetsCanBeChosen() {
        show(cameraOnly)

        composeRule.onNodeWithText("All folders").performClick()
        composeRule.onNodeWithText("Done").performClick()

        events shouldBe listOf("preset EVERYTHING", "done")
    }

    @Test
    fun handPickedFoldersExplainThatOnlyThoseAreSent() {
        show(cameraOnly.copy(selection = SourceSelection.of(setOf("DCIM/Camera/"))))

        composeRule.onNodeWithText("Only the folders you check are sent.").assertIsDisplayed()
    }

    @Test
    fun aPhoneWithoutPhotosSaysSo() {
        show(SourceFoldersUiState.Ready(SourceSelection.Default, emptyList()))

        composeRule.onNodeWithText("No photos or videos on this phone yet.").assertIsDisplayed()
    }

    @Test
    fun screenshot_light() = screenshot(cameraOnly, "producer_folders_light")

    @Test
    fun screenshot_dark() = screenshot(
        SourceFoldersUiState.Ready(
            SourceSelection(SourcePreset.CAMERA_AND_SCREENSHOTS),
            folders.map { it.copy(included = it.folder.name in setOf("Camera", "Screenshots")) },
        ),
        "producer_folders_dark",
        darkTheme = true,
    )

    @Test
    fun screenshot_loading_light() = screenshot(SourceFoldersUiState.Loading, "producer_folders_loading_light")

    private fun screenshot(state: SourceFoldersUiState, name: String, darkTheme: Boolean = false) {
        show(state, darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: SourceFoldersUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                SourceFoldersScreen(
                    state = state,
                    onChoosePreset = { events += "preset $it" },
                    onToggle = { events += "toggle $it" },
                    onDone = { events += "done" },
                )
            }
        }
    }
}
