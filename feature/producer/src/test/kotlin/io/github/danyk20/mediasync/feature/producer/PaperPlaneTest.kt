/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.engine.SendingItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PaperPlaneTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val photo = SendingItem("content://media/external/images/media/7", "PXL_7.jpg", 4_000_000, false, 0, 0)

    @Test
    fun itSaysWhichFileIsSent() {
        show(photo, fraction = 0.5f)
        composeRule.onNodeWithContentDescription("Sending PXL_7.jpg").assertExists()

        show(photo.copy(key = SendingItem.TEST_FILE_KEY), fraction = 0.5f)
        composeRule.onNodeWithContentDescription("Sending the test file").assertExists()
    }

    @Test
    fun screenshot_flat_light() = screenshot(0f, "paper_plane_flat_light")

    @Test
    fun screenshot_folding_light() = screenshot(0.2f, "paper_plane_folding_light")

    @Test
    fun screenshot_flying_light() = screenshot(0.6f, "paper_plane_flying_light")

    @Test
    fun screenshot_leaving_light() = screenshot(0.95f, "paper_plane_leaving_light")

    @Test
    fun screenshot_flat_dark() = screenshot(0f, "paper_plane_flat_dark", darkTheme = true)

    @Test
    fun screenshot_folding_dark() = screenshot(0.2f, "paper_plane_folding_dark", darkTheme = true)

    @Test
    fun screenshot_flying_dark() = screenshot(0.6f, "paper_plane_flying_dark", darkTheme = true)

    @Test
    fun screenshot_leaving_dark() = screenshot(0.95f, "paper_plane_leaving_dark", darkTheme = true)

    @Test
    fun screenshot_videoWithoutThumbnail_light() =
        screenshot(0.25f, "paper_plane_video_plain_light", item = photo.copy(video = true), withPhoto = false)

    @Test
    fun screenshot_testFile_light() =
        screenshot(0.3f, "paper_plane_test_file_light", item = photo.copy(key = SendingItem.TEST_FILE_KEY))

    private fun screenshot(
        fraction: Float,
        name: String,
        item: SendingItem = photo,
        darkTheme: Boolean = false,
        withPhoto: Boolean = true,
    ) {
        show(item, fraction, darkTheme, withPhoto)
        composeRule.onRoot().captureScreenshot(name)
    }

    private var shown by mutableStateOf<SendingItem?>(null)

    private fun show(item: SendingItem, fraction: Float, darkTheme: Boolean = false, withPhoto: Boolean = true) {
        val first = shown == null
        shown = item
        if (!first) {
            composeRule.waitForIdle()
            return
        }
        val sunset = if (withPhoto && item.key != SendingItem.TEST_FILE_KEY) sunset() else null
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                Box(Modifier.padding(8.dp)) {
                    PaperPlane(shown, bytesPerSecond = null, Modifier.size(380.dp, 180.dp), fraction, sunset)
                }
            }
        }
    }
}

/** A photo for the tests: a sunset over hills, wider than it's high. */
internal fun sunset(): Bitmap {
    val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, 240f, 0xFF2E3A87.toInt(), 0xFFFF9A5A.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 320f, 240f, paint)
    paint.shader = null
    paint.color = 0xFFFFE08A.toInt()
    canvas.drawCircle(190f, 150f, 34f, paint)
    paint.color = 0xFF3B2F4A.toInt()
    canvas.drawPath(
        Path().apply {
            moveTo(0f, 240f)
            lineTo(0f, 170f)
            quadTo(70f, 130f, 140f, 175f)
            quadTo(220f, 215f, 320f, 160f)
            lineTo(320f, 240f)
            close()
        },
        paint,
    )
    paint.color = 0xFF231B2E.toInt()
    canvas.drawPath(
        Path().apply {
            moveTo(0f, 240f)
            lineTo(0f, 210f)
            quadTo(120f, 185f, 320f, 215f)
            lineTo(320f, 240f)
            close()
        },
        paint,
    )
    return bitmap
}
