/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("MagicNumber") // Offsets into flat arrays of corners: x and y, three times.

package io.github.danyk20.mediasync.feature.producer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withTranslation
import io.github.danyk20.mediasync.feature.producer.PlaneModel.Companion.CORNER_FLOATS
import io.github.danyk20.mediasync.feature.producer.PlaneModel.Companion.FACETS
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the paper airplane (docs/PLAN.md §3.3) on a [Canvas]: the sheet folding, then flying off along a
 * [FlightPath] with a faint dashed trail. Every object it draws with is made once; a frame allocates nothing.
 *
 * Each folded triangle shows its part of the sheet's bitmap, mapped corner to corner, or the paper's plain back; then
 * a shade by how the light falls on it, and its creases. A soft shadow under the whole lifts as it flies.
 */
@Suppress("TooManyFunctions") // The steps of drawing a frame.
internal class PlaneRenderer(private val density: Float) {
    private val model = PlaneModel()
    private val matrix = Matrix()
    private val facet = Path()
    private val shadow = Path()
    private val trail = Path()
    private val ring = RectF()
    private val corners = FloatArray(CORNER_FLOATS)

    /** Each triangle's corners in the sheet's bitmap. */
    private val source = FloatArray(FACETS * CORNER_FLOATS).also { source ->
        for (i in source.indices step 2) {
            source[i] = (model.sheet[i] + 1f) / 2f * SHEET_PX
            source[i + 1] = (1f - model.sheet[i + 1]) / 2f * SHEET_PX
        }
    }

    // A stroke as wide as a pixel on the fill closes the hairline seams between triangles.
    private val front = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 1f
        strokeJoin = Paint.Join.ROUND
    }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 1f
        strokeJoin = Paint.Join.ROUND
        color = PAPER_BACK
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CREASE_DP * density
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(SHADOW_BLUR_DP * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TRAIL_DP * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, GAP_DP * density), 0f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
        strokeCap = Paint.Cap.ROUND
    }

    private var sheet: Bitmap? = null
    private var shader: BitmapShader? = null
    private var flight: FlightPath? = null
    private var flightWidth = 0f
    private var flightHeight = 0f

    private var trailColor = 0
    private var ringColor = 0
    private var trackColor = 0

    /** The theme's colors: [trail], the dashes behind the airplane; [ring] and [track], the still ring's. */
    fun colors(trail: Int, ring: Int, track: Int) {
        trailColor = trail
        ringColor = ring
        trackColor = track
    }

    /**
     * One frame in a [width] by [height] card: [sheet] folded and flown [fraction] of the way (folded at
     * [PlaneTimeline.FOLDED], gone at 1), faded in [appear] of the way.
     */
    @Suppress("LongParameterList") // A frame: where, what, and how far.
    fun draw(canvas: Canvas, width: Float, height: Float, sheet: Bitmap, fraction: Float, appear: Float) {
        use(sheet)
        val path = flightFor(width, height)
        val fold = (fraction / PlaneTimeline.FOLDED).coerceIn(0f, 1f)
        val fly = ((fraction - PlaneTimeline.FOLDED) / (1f - PlaneTimeline.FOLDED)).coerceIn(0f, 1f)
        // It launches slowly, then speeds away: still as far along as the file is.
        val t = fly * sqrt(fly)
        val turned = smoothstep(TURN_START, 1f, fold)
        model.fold(fold)
        model.pose(
            heading = turned * path.heading(t),
            pitch = turned * PITCH,
            roll = turned * (ROLL + BANK * sin(PI.toFloat() * fly)),
        )
        val grow = APPEAR_SCALE + (1f - APPEAR_SCALE) * appear
        val scale = height * SHEET_SHARE * grow * (1f - SHRINK * smoothstep(0f, 1f, fly))
        val alpha = appear * (1f - smoothstep(FADE_START, 1f, fly))
        if (alpha <= 0f) return
        if (fly > 0f) drawTrail(canvas, path, t, alpha)
        val layered = alpha < 1f
        if (layered) canvas.saveLayerAlpha(0f, 0f, width, height, (alpha * OPAQUE).toInt())
        val x = path.x(t)
        val y = path.y(t)
        drawShadow(canvas, x, y, scale, lift = fold * FOLD_LIFT + fly)
        for (k in 0 until FACETS) drawFacet(canvas, model.order[k], x, y, scale, fold)
        if (layered) canvas.restore()
    }

    /**
     * Without motion (the phone's animations off): [sheet] flat in the middle, and a ring around it going round as
     * [fraction] of the file goes.
     */
    fun drawStill(canvas: Canvas, width: Float, height: Float, sheet: Bitmap, fraction: Float) {
        use(sheet)
        model.fold(0f)
        model.pose(heading = 0f, pitch = 0f, roll = 0f)
        val scale = height * STILL_SHARE
        val x = width / 2f
        val y = height / 2f
        drawShadow(canvas, x, y, scale, lift = 0f)
        for (k in 0 until FACETS) drawFacet(canvas, model.order[k], x, y, scale, fold = 0f)
        val radius = scale * RING_REACH
        ring.set(x - radius, y - radius, x + radius, y + radius)
        ringPaint.color = trackColor
        canvas.drawArc(ring, 0f, FULL_TURN, false, ringPaint)
        ringPaint.color = ringColor
        canvas.drawArc(ring, -QUARTER_TURN, FULL_TURN * fraction.coerceIn(0f, 1f), false, ringPaint)
    }

    private fun use(sheet: Bitmap) {
        if (sheet === this.sheet) return
        this.sheet = sheet
        shader = BitmapShader(sheet, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { front.shader = it }
    }

    private fun flightFor(width: Float, height: Float): FlightPath {
        flight?.takeIf { width == flightWidth && height == flightHeight }?.let { return it }
        flightWidth = width
        flightHeight = height
        return FlightPath(width, height).also { flight = it }
    }

    /** The triangle's corners on the canvas, around ([x], [y]), into [corners]; `false` if it's seen edge-on. */
    private fun place(index: Int, x: Float, y: Float, scale: Float): Boolean {
        val o = index * CORNER_FLOATS
        for (c in 0 until CORNER_FLOATS step 2) {
            corners[c] = x + model.screen[o + c] * scale
            corners[c + 1] = y - model.screen[o + c + 1] * scale
        }
        val area = (corners[2] - corners[0]) * (corners[5] - corners[1]) -
            (corners[3] - corners[1]) * (corners[4] - corners[0])
        return area * area > MIN_AREA_PX
    }

    private fun trianglePath(path: Path, clockwise: Boolean) {
        path.moveTo(corners[0], corners[1])
        if (clockwise) {
            path.lineTo(corners[2], corners[3])
            path.lineTo(corners[4], corners[5])
        } else {
            path.lineTo(corners[4], corners[5])
            path.lineTo(corners[2], corners[3])
        }
        path.close()
    }

    @Suppress("LongParameterList") // Where the airplane is, and how folded.
    private fun drawFacet(canvas: Canvas, index: Int, x: Float, y: Float, scale: Float, fold: Float) {
        if (!place(index, x, y, scale)) return
        facet.rewind()
        trianglePath(facet, clockwise = true)
        if (model.facing[index]) {
            matrix.setPolyToPoly(source, index * CORNER_FLOATS, corners, 0, PlaneModel.CORNERS)
            shader?.setLocalMatrix(matrix)
            canvas.drawPath(facet, front)
        } else {
            canvas.drawPath(facet, back)
        }
        val light = model.light[index]
        shade.color = if (light < 0f) {
            alphaOf(min(1f, -light * DARKEN), SHADE_DARK)
        } else {
            alphaOf(min(1f, light * LIGHTEN), SHADE_LIGHT)
        }
        canvas.drawPath(facet, shade)
        // Creases show as it folds; the sheet's edge, always a little.
        crease.color = alphaOf(CREASE_FLAT + CREASE_FOLDED * smoothstep(0f, CREASE_IN, fold), CREASE)
        canvas.drawPath(facet, crease)
    }

    /** The airplane's outline, soft, below it and to the right: farther as it [lift]s. */
    private fun drawShadow(canvas: Canvas, x: Float, y: Float, scale: Float, lift: Float) {
        shadow.rewind()
        for (index in 0 until FACETS) {
            if (!place(index, x, y, scale)) continue
            // All one way round, so where triangles overlap they add up instead of cancelling out.
            val clockwise = (corners[2] - corners[0]) * (corners[5] - corners[1]) -
                (corners[3] - corners[1]) * (corners[4] - corners[0]) > 0f
            trianglePath(shadow, clockwise)
        }
        val offset = (SHADOW_DP + SHADOW_LIFT_DP * lift) * density
        shadowPaint.color = alphaOf(SHADOW_ALPHA * (1f - lift * SHADOW_FADE).coerceAtLeast(0f), SHADE_DARK)
        canvas.withTranslation(offset * SHADOW_SIDEWAYS, offset) { drawPath(shadow, shadowPaint) }
    }

    private fun drawTrail(canvas: Canvas, path: FlightPath, t: Float, alpha: Float) {
        trail.rewind()
        trail.moveTo(path.x(0f), path.y(0f))
        for (step in 1..TRAIL_STEPS) {
            val at = t * step / TRAIL_STEPS
            trail.lineTo(path.x(at), path.y(at))
        }
        trailPaint.color = alphaOf(TRAIL_ALPHA * alpha, trailColor)
        canvas.drawPath(trail, trailPaint)
    }

    private fun alphaOf(alpha: Float, color: Int): Int =
        ((alpha.coerceIn(0f, 1f) * (color ushr ALPHA_SHIFT)).toInt() shl ALPHA_SHIFT) or (color and RGB)

    private companion object {
        /** The flat sheet's half-size, of the card's height; it flies off at [SHRINK] smaller. */
        const val SHEET_SHARE = 0.31f
        const val STILL_SHARE = 0.27f
        const val SHRINK = 0.5f
        const val APPEAR_SCALE = 0.86f
        const val RING_REACH = 1.55f

        /** It turns its nose towards the way out from here on of its folding. */
        const val TURN_START = 0.25f

        /** Seen from above and a little behind, banking. */
        const val PITCH = 0.62f
        const val ROLL = -0.42f
        const val BANK = -0.22f
        const val FADE_START = 0.78f
        const val FOLD_LIFT = 0.15f

        const val PAPER_BACK = 0xFFF1EDE4.toInt()
        const val SHADE_DARK = 0xFF1B1A17.toInt()
        const val SHADE_LIGHT = 0xFFFFFFFF.toInt()
        const val CREASE = 0xFF3A362E.toInt()
        const val DARKEN = 0.75f
        const val LIGHTEN = 0.55f
        const val CREASE_FLAT = 0f
        const val CREASE_FOLDED = 0.2f
        const val CREASE_IN = 0.25f
        const val CREASE_DP = 0.7f

        const val SHADOW_DP = 2f
        const val SHADOW_LIFT_DP = 12f
        const val SHADOW_BLUR_DP = 5f
        const val SHADOW_ALPHA = 0.22f
        const val SHADOW_FADE = 0.6f
        const val SHADOW_SIDEWAYS = 0.4f

        const val TRAIL_STEPS = 28
        const val TRAIL_ALPHA = 0.55f
        const val TRAIL_DP = 1.5f
        const val DASH_DP = 4f
        const val GAP_DP = 6f
        const val RING_DP = 3f

        const val FULL_TURN = 360f
        const val QUARTER_TURN = 90f
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
        const val RGB = 0xFFFFFF

        /** Below this (twice the area, squared, in pixels) a triangle is edge-on: nothing to draw. */
        const val MIN_AREA_PX = 0.01f
    }
}
