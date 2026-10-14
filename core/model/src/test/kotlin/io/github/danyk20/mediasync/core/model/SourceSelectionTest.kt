/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceSelectionTest {

    private val camera = "DCIM/Camera/"
    private val pixelScreenshots = "Pictures/Screenshots/"
    private val samsungScreenshots = "DCIM/Screenshots/"
    private val whatsApp = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
    private val received = "DCIM/MediaSync/"
    private val all = listOf(camera, pixelScreenshots, samsungScreenshots, whatsApp, received)

    private fun SourceSelection.sent() = all.filter(::includes)

    @Test
    fun cameraOnlyIsTheDefault() {
        SourceSelection.Default.preset shouldBe SourcePreset.CAMERA
        SourceSelection.Default.sent() shouldBe listOf(camera)
    }

    @Test
    fun cameraFoldersAreThoseUnderDcimWhateverTheirName() {
        SourceSelection.isCameraFolder("DCIM/100ANDRO/") shouldBe true
        SourceSelection.isCameraFolder("dcim/camera/") shouldBe true
        SourceSelection.isCameraFolder("DCIM/") shouldBe true
        SourceSelection.isCameraFolder("Pictures/Camera/") shouldBe false
    }

    @Test
    fun screenshotsAreFoundWhereverThePhoneKeepsThem() {
        SourceSelection(SourcePreset.CAMERA_AND_SCREENSHOTS).sent() shouldBe
            listOf(camera, pixelScreenshots, samsungScreenshots)
    }

    @Test
    fun everythingLeavesOutWhatThisPhoneReceived() {
        SourceSelection(SourcePreset.EVERYTHING).sent() shouldBe
            listOf(camera, pixelScreenshots, samsungScreenshots, whatsApp)
        SourceSelection(SourcePreset.EVERYTHING).includes("DCIM/MediaSync/Anna/") shouldBe false
    }

    @Test
    fun handPickedFoldersAreExactlyThoseChosen() {
        SourceSelection.of(setOf(whatsApp, received)).sent() shouldBe listOf(whatsApp, received)
        SourceSelection.of(emptySet()).sent() shouldBe emptyList()
        // A subfolder isn't chosen along with its parent; it's listed and chosen on its own.
        SourceSelection.of(setOf(camera)).includes("DCIM/Camera/Raw/") shouldBe false
    }

    @Test
    fun aFoldersNameIsItsLastPart() {
        SourceSelection.nameOf(whatsApp) shouldBe "WhatsApp Images"
        SourceSelection.nameOf("DCIM/") shouldBe "DCIM"
    }
}
