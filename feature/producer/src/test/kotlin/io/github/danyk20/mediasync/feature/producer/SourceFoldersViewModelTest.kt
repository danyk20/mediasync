/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import io.github.danyk20.mediasync.core.media.MediaFolder
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SourceFoldersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val camera = MediaFolder("DCIM/Camera/", 2_814, 9_400_000_000, "content://media/external/images/media/9")
    private val screenshots = MediaFolder("Pictures/Screenshots/", 412, 310_000_000, null)
    private val whatsApp =
        MediaFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 3_020, 1_200_000_000, null)
    private val library = FakeMediaLibrary(listOf(whatsApp, camera, screenshots))
    private val settings = FakeSettingsRepository()
    private val viewModel by lazy { SourceFoldersViewModel(library, settings) }

    private suspend fun ready() = viewModel.uiState.first {
        it is SourceFoldersUiState.Ready
    } as SourceFoldersUiState.Ready

    private suspend fun sent() = ready().folders.filter { it.included }.map { it.folder }

    @Test
    fun listsEveryFolderWithTheCameraChosenByDefault() = runTest {
        val state = ready()

        state.selection shouldBe SourceSelection.Default
        state.folders.map { it.folder } shouldBe listOf(whatsApp, camera, screenshots)
        sent() shouldBe listOf(camera)
    }

    @Test
    fun aPresetIsSavedAndApplied() = runTest {
        ready()

        viewModel.choose(SourcePreset.CAMERA_AND_SCREENSHOTS)

        settings.sourceSelection.value shouldBe SourceSelection(SourcePreset.CAMERA_AND_SCREENSHOTS)
        viewModel.uiState.first {
            (it as SourceFoldersUiState.Ready).selection.preset ==
                SourcePreset.CAMERA_AND_SCREENSHOTS
        }
        sent() shouldBe listOf(camera, screenshots)
    }

    @Test
    fun turningAFolderOnStartsFromWhatThePresetCovers() = runTest {
        ready()

        viewModel.toggle(whatsApp.path)

        settings.sourceSelection.value shouldBe SourceSelection.of(setOf(camera.path, whatsApp.path))
    }

    @Test
    fun turningAFolderOffKeepsTheOtherChosenOnes() = runTest {
        // Includes a folder that has no photos at the moment; it stays chosen.
        settings.setSourceSelection(SourceSelection.of(setOf(camera.path, whatsApp.path, "Pictures/Emptied/")))
        viewModel.uiState.first {
            (it as? SourceFoldersUiState.Ready)?.selection?.preset == null &&
                it is SourceFoldersUiState.Ready
        }

        viewModel.toggle(camera.path)

        settings.sourceSelection.value shouldBe SourceSelection.of(setOf(whatsApp.path, "Pictures/Emptied/"))
    }

    @Test
    fun newFoldersAppearWithoutReopening() = runTest {
        ready()
        val instagram = MediaFolder("Pictures/Instagram/", 12, 40_000_000, null)

        library.change(listOf(whatsApp, camera, screenshots, instagram))

        val state = viewModel.uiState.first { (it as SourceFoldersUiState.Ready).folders.size == 4 }
        state.shouldBeInstanceOf<SourceFoldersUiState.Ready>().folders.last() shouldBe
            FolderChoice(instagram, included = false)
    }
}
