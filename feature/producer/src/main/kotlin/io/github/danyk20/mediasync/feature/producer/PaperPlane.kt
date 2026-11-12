/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.sync.engine.SendingItem

/**
 * The sending animation (docs/PLAN.md §3.3): [item]'s thumbnail, on a sheet of paper, folds into a paper airplane
 * that flies off as fast as the file goes over the link; then the next file's sheet comes. With the phone's
 * animations off, the sheet stays still, with a ring going round instead.
 *
 * [bytesPerSecond]: the transfer's speed, for the first moments of a file. For tests and previews: [fraction] draws
 * that point of the animation, still, and [photo] stands in for the gallery's thumbnail.
 */
@Composable
internal fun PaperPlane(
    item: SendingItem?,
    bytesPerSecond: Long?,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    photo: Bitmap? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scene = remember(density) { PlaneScene(context, density) }
    val latest by rememberUpdatedState(item)
    val rate by rememberUpdatedState(bytesPerSecond)
    val fixed = remember(photo, item?.key, item?.video) { photo?.let { paperSheet(it, item?.video == true) } }
    LaunchedEffect(item?.key) { item?.let { scene.sheets.load(it) } }
    if (fraction == null && !scene.still) {
        LaunchedEffect(scene) {
            // Made once: a frame allocates nothing.
            val onFrame: (Long) -> Unit = { now ->
                scene.timeline.advance(now, latest, rate)
                scene.frame.longValue = now
            }
            // Infinite, as far as tests go: they draw the first frame.
            while (true) withInfiniteAnimationFrameNanos(onFrame)
        }
    }
    val colors = MaterialTheme.colorScheme
    val description = item?.let {
        if (it.key == SendingItem.TEST_FILE_KEY) {
            stringResource(R.string.producer_plane_test_file)
        } else {
            stringResource(R.string.producer_plane_sending, it.name)
        }
    }
    Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerLow)
            .semantics { description?.let { contentDescription = it } },
    ) {
        scene.frame.longValue // Every frame.
        scene.sheets.version // A thumbnail is ready.
        scene.renderer.colors(colors.onSurfaceVariant.toArgb(), colors.primary.toArgb(), colors.surfaceVariant.toArgb())
        drawIntoCanvas { canvas -> scene.draw(canvas.nativeCanvas, size, item, fraction, fixed) }
    }
}

/** What the airplane is drawn with, made once: the sheets, the timeline, and the renderer. */
private class PlaneScene(context: Context, density: Float) {
    val sheets = PaperSheets(context)
    val renderer = PlaneRenderer(density)
    val timeline = PlaneTimeline()
    val frame = mutableLongStateOf(0L)
    val still = animationsOff(context)

    /** One frame: [fraction] fixed (tests), still, before the first frame, or as the timeline is. */
    fun draw(canvas: android.graphics.Canvas, size: Size, item: SendingItem?, fraction: Float?, fixed: Bitmap?) {
        val (width, height) = size
        val shown = timeline.shown
        when {
            item != null && fraction != null ->
                renderer.draw(canvas, width, height, fixed ?: sheets.sheetFor(item), fraction, 1f)

            item != null && still -> renderer.drawStill(
                canvas,
                width,
                height,
                fixed ?: sheets.sheetFor(item),
                if (item.size > 0) item.bytesSent.toFloat() / item.size else 1f,
            )

            // Before the first frame: the sheet, flat.
            shown == null && item != null ->
                renderer.draw(canvas, width, height, fixed ?: sheets.sheetFor(item), 0f, 1f)

            shown != null && timeline.fraction < 1f ->
                renderer.draw(
                    canvas,
                    width,
                    height,
                    fixed ?: sheets.sheetFor(shown),
                    timeline.fraction,
                    timeline.appear,
                )
        }
    }
}

/** The phone's *Animator duration scale* is off, as for *Remove animations*. */
private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
