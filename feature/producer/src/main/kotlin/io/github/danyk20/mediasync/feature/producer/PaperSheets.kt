/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import io.github.danyk20.mediasync.sync.engine.SendingItem
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sheets of paper the airplanes fold (docs/PLAN.md §3.3): each file's thumbnail printed on paper, with a margin,
 * and a play badge for a video. Until a thumbnail is read, and where there's none (the test file, a file that can't be
 * read), a plain sheet: a picture, a video or ruled lines.
 */
internal class PaperSheets(private val context: Context) {
    private val ready = LruCache<String, Bitmap>(KEPT)
    private val plain = arrayOfNulls<Bitmap>(Plain.entries.size)

    /** Changes when a sheet is ready, for whoever draws without a frame loop. */
    var version by mutableIntStateOf(0)
        private set

    /** Makes [item]'s sheet from its thumbnail, or [photo] instead; nothing if it's there, or there's no thumbnail. */
    suspend fun load(item: SendingItem, photo: Bitmap? = null) {
        if (ready.get(item.key) != null) return
        val sheet = withContext(Dispatchers.IO) {
            val thumbnail = photo ?: item.key.takeIf { it != SendingItem.TEST_FILE_KEY }?.let { key ->
                MediaThumbnails.load(context, MediaThumbnails.uriOf(key), item.video)
            }
            thumbnail?.let { paperSheet(it, item.video) }
        } ?: return
        ready.put(item.key, sheet)
        version++
    }

    /** [item]'s sheet: its thumbnail's once that's ready, a plain one until then. */
    fun sheetFor(item: SendingItem): Bitmap = ready.get(item.key) ?: plainSheet(
        when {
            item.key == SendingItem.TEST_FILE_KEY -> Plain.TEST_FILE
            item.video -> Plain.VIDEO
            else -> Plain.PHOTO
        },
    )

    private fun plainSheet(kind: Plain): Bitmap =
        plain[kind.ordinal] ?: plainPaperSheet(kind).also { plain[kind.ordinal] = it }

    private companion object {
        /** The sheet shown, the next one, and a few back. */
        const val KEPT = 6
    }
}

/** Sheets without a thumbnail. */
internal enum class Plain { PHOTO, VIDEO, TEST_FILE }

/** A sheet's side, in pixels: the thumbnail's size. */
internal const val SHEET_PX = MediaThumbnails.THUMBNAIL_PX

private const val MARGIN_PX = 12f
private const val EDGE_PX = 1.5f
private const val PAPER = 0xFFFBFAF6.toInt()
private const val PHOTO_EDGE = 0x22000000
private const val BADGE = 0x99000000.toInt()
private const val BADGE_RADIUS = 22f
private const val BADGE_INSET = 34f
private const val SKY = 0xFFDCE6F0.toInt()
private const val SUN = 0xFFF2C14E.toInt()
private const val FAR_HILL = 0xFFA9BED0.toInt()
private const val NEAR_HILL = 0xFF7F98AE.toInt()
private const val RULE = 0xFFD9D4C7.toInt()
private const val HEADING_RULE = 0xFFB9B2A2.toInt()
private const val RULES = 9

/** [photo] printed on a sheet of paper, cropped to a square; with a play badge for a [video]. */
internal fun paperSheet(photo: Bitmap, video: Boolean): Bitmap = blankSheet { canvas, area ->
    val side = min(photo.width, photo.height)
    val left = (photo.width - side) / 2
    val top = (photo.height - side) / 2
    canvas.drawBitmap(photo, Rect(left, top, left + side, top + side), area, Paint(Paint.FILTER_BITMAP_FLAG))
    if (video) playBadge(canvas, area)
}

/** A sheet with no thumbnail on it: a picture or a video drawn plainly, or ruled lines for the test file. */
@Suppress("MagicNumber") // A drawing: where the sun and the hills are.
internal fun plainPaperSheet(kind: Plain): Bitmap = blankSheet { canvas, area ->
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (kind == Plain.TEST_FILE) {
        val step = area.height() / (RULES + 1)
        paint.strokeWidth = 3f
        for (rule in 1..RULES) {
            paint.color = if (rule == 1) HEADING_RULE else RULE
            val y = area.top + step * rule
            val end = if (rule == RULES) area.centerX() else area.right - MARGIN_PX
            canvas.drawLine(area.left + MARGIN_PX, y, end, y, paint)
        }
        return@blankSheet
    }
    paint.color = SKY
    canvas.drawRect(area, paint)
    paint.color = SUN
    canvas.drawCircle(area.left + area.width() * 0.72f, area.top + area.height() * 0.3f, area.width() * 0.1f, paint)
    paint.color = FAR_HILL
    canvas.drawPath(hill(area, peak = 0.68f, height = 0.42f, from = 0.25f), paint)
    paint.color = NEAR_HILL
    canvas.drawPath(hill(area, peak = 0.3f, height = 0.55f, from = -0.2f), paint)
    if (kind == Plain.VIDEO) playBadge(canvas, area)
}

/** A blank sheet; [print] draws within its margin. */
private fun blankSheet(print: (Canvas, RectF) -> Unit): Bitmap {
    val sheet = createBitmap(SHEET_PX, SHEET_PX)
    val canvas = Canvas(sheet)
    canvas.drawColor(PAPER)
    val area = RectF(MARGIN_PX, MARGIN_PX, SHEET_PX - MARGIN_PX, SHEET_PX - MARGIN_PX)
    canvas.withClip(area) { print(this, area) }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = PHOTO_EDGE
        strokeWidth = EDGE_PX
    }
    canvas.drawRect(area, edge)
    return sheet
}

/** A hill peaking at [peak] of the width, [height] of the area high, from [from] to the far side. */
private fun hill(area: RectF, peak: Float, height: Float, from: Float) = Path().apply {
    moveTo(area.left + area.width() * from, area.bottom)
    lineTo(area.left + area.width() * peak, area.bottom - area.height() * height)
    lineTo(area.left + area.width() * (2 * peak - from), area.bottom)
    close()
}

@Suppress("MagicNumber") // A drawing: the play triangle's shape.
private fun playBadge(canvas: Canvas, area: RectF) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE }
    val cx = area.left + BADGE_INSET
    val cy = area.bottom - BADGE_INSET
    canvas.drawCircle(cx, cy, BADGE_RADIUS, paint)
    paint.color = Color.WHITE
    val r = BADGE_RADIUS * 0.45f
    canvas.drawPath(
        Path().apply {
            moveTo(cx - r * 0.6f, cy - r)
            lineTo(cx + r, cy)
            lineTo(cx - r * 0.6f, cy + r)
            close()
        },
        paint,
    )
}
