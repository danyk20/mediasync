/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("MagicNumber") // Offsets into flat arrays of corners (x, y and z, three times), and Bézier's formula.

package io.github.danyk20.mediasync.feature.producer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A square sheet of paper folded into a dart (docs/PLAN.md §3.3, *Sending animation*), as rigid triangles in 3D.
 *
 * The sheet spans -1 to 1 both ways, its top (the photo's top, and the airplane's nose) at y = 1, facing the viewer
 * along +z. It's cut along every crease into [FACETS] triangles, five a side: the top corner's flap (in two, as the
 * wing's crease runs through it once folded), the wing (in two) and the keel. Then, by [fold] from 0 to 1, as a dart
 * is folded: the top corners to the middle, the sheet in half (the flaps inside), and the wings down, flat.
 *
 * [pose] turns it as it flies, and projects it: every frame, into arrays made once, so nothing is allocated while it
 * animates. Each triangle's corners in the sheet stay where they are in the photo, so the photo folds with the paper.
 */
@Suppress("TooManyFunctions") // Small steps of one computation, each reused per frame.
internal class PlaneModel {
    /** Each triangle's corners in the sheet: x, y, three times. */
    val sheet = FloatArray(FACETS * CORNER_FLOATS)

    /** Each triangle's corners folded, then turned and projected: x, y, three times; y up, the sheet's half-size 1. */
    val screen = FloatArray(FACETS * CORNER_FLOATS)

    /** How near each triangle is, for drawing the far ones first. */
    val depth = FloatArray(FACETS)

    /** The photo's side of the triangle faces the viewer; otherwise its back, plain paper. */
    val facing = BooleanArray(FACETS)

    /** How the light falls on the side seen: below 0 darker, above 0 lighter than the flat sheet. */
    val light = FloatArray(FACETS)

    /** The triangles from the farthest to the nearest. */
    val order = IntArray(FACETS)

    private val part = IntArray(FACETS)
    private val right = BooleanArray(FACETS)

    /** Each triangle's corners folded, before turning: x, y, z three times. */
    val folded = FloatArray(FACETS * WORLD_FLOATS)

    /** Folded and turned, before perspective. */
    private val world = FloatArray(FACETS * WORLD_FLOATS)

    /** The front's orientation in the sheet: +1 counterclockwise. */
    private val winding = FloatArray(FACETS)

    // A point as it's moved, step by step; no objects per point.
    private var px = 0f
    private var py = 0f
    private var pz = 0f

    init {
        for (side in 0..1) {
            val mirror = if (side == 0) 1f else -1f
            LEFT.forEachIndexed { index, corners ->
                val facet = side * LEFT.size + index
                part[facet] = PARTS[index]
                right[facet] = side == 1
                for (c in 0 until CORNERS) {
                    sheet[facet * CORNER_FLOATS + c * 2] = corners[c * 2] * mirror
                    sheet[facet * CORNER_FLOATS + c * 2 + 1] = corners[c * 2 + 1]
                }
                winding[facet] = if (cross2(facet, sheet) >= 0f) 1f else -1f
            }
        }
    }

    /** Folds the sheet [fold] of the way, from 0 (flat) to 1 (a dart). */
    fun fold(fold: Float) {
        val corners = (PI - FLAP_SPRING).toFloat() * smoothstep(0f, CORNERS_END, fold)
        val half = HALF_ANGLE * smoothstep(HALF_START, HALF_END, fold)
        val wings = (HALF_ANGLE - DIHEDRAL) * smoothstep(WINGS_START, 1f, fold)
        for (facet in 0 until FACETS) {
            val mirror = if (right[facet]) -1f else 1f
            for (c in 0 until CORNERS) {
                // The left half's moves; the right half's mirror them.
                px = sheet[facet * CORNER_FLOATS + c * 2] * mirror
                py = sheet[facet * CORNER_FLOATS + c * 2 + 1]
                pz = 0f
                if (part[facet] == FLAP_KEEL || part[facet] == FLAP_WING) {
                    rotate(0f, 1f, CORNER_AXIS_X, CORNER_AXIS_Y, -corners)
                }
                if (part[facet] != KEEL && part[facet] != FLAP_KEEL) {
                    rotate(0f, 1f, WING_AXIS_X, WING_AXIS_Y, wings)
                }
                turnAboutY(half)
                val w = facet * WORLD_FLOATS + c * 3
                folded[w] = px * mirror
                folded[w + 1] = py
                folded[w + 2] = pz
            }
        }
    }

    /**
     * Turns the folded sheet: [roll] about its nose's line, then [pitch] about the side-to-side line, then [heading]
     * on the screen, counterclockwise from the nose pointing up; all in radians. Then projects it with a little
     * perspective, and works out what each triangle shows and how lit it is.
     */
    fun pose(heading: Float, pitch: Float, roll: Float) {
        val cr = cos(roll)
        val sr = sin(roll)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val ch = cos(heading)
        val sh = sin(heading)
        for (facet in 0 until FACETS) {
            var near = 0f
            for (c in 0 until CORNERS) {
                val w = facet * WORLD_FLOATS + c * 3
                // Roll about y.
                val x1 = folded[w] * cr + folded[w + 2] * sr
                val z1 = -folded[w] * sr + folded[w + 2] * cr
                // Pitch about x.
                val y2 = folded[w + 1] * cp - z1 * sp
                val z2 = folded[w + 1] * sp + z1 * cp
                // Heading about z.
                val x3 = x1 * ch - y2 * sh
                val y3 = x1 * sh + y2 * ch
                val scale = CAMERA / (CAMERA - z2)
                screen[facet * CORNER_FLOATS + c * 2] = x3 * scale
                screen[facet * CORNER_FLOATS + c * 2 + 1] = y3 * scale
                // For the normal: turned, before perspective.
                world[w] = x3
                world[w + 1] = y3
                world[w + 2] = z2
                near += z2
            }
            depth[facet] = near / CORNERS
            shade(facet)
        }
        sortByDepth()
    }

    /** The photo's side faces the viewer or not, and how the light falls on the side seen. */
    private fun shade(facet: Int) {
        val w = facet * WORLD_FLOATS
        val ax = world[w + 3] - world[w]
        val ay = world[w + 4] - world[w + 1]
        val az = world[w + 5] - world[w + 2]
        val bx = world[w + 6] - world[w]
        val by = world[w + 7] - world[w + 1]
        val bz = world[w + 8] - world[w + 2]
        var nx = (ay * bz - az * by) * winding[facet]
        var ny = (az * bx - ax * bz) * winding[facet]
        var nz = (ax * by - ay * bx) * winding[facet]
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length < EPSILON) {
            facing[facet] = true
            light[facet] = 0f
            return
        }
        facing[facet] = nz >= 0f
        val seen = if (facing[facet]) 1f / length else -1f / length
        nx *= seen
        ny *= seen
        nz *= seen
        light[facet] = (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z) - LIGHT_Z
    }

    /** Insertion sort: ten items, nearly sorted from the last frame, and no allocation. */
    private fun sortByDepth() {
        for (i in 0 until FACETS) order[i] = i
        for (i in 1 until FACETS) {
            val facet = order[i]
            var j = i - 1
            while (j >= 0 && depth[order[j]] > depth[facet]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = facet
        }
    }

    /** Rotates the point by [angle] about the line through ([ox], [oy], 0) along the unit ([kx], [ky], 0). */
    private fun rotate(ox: Float, oy: Float, kx: Float, ky: Float, angle: Float) {
        if (angle == 0f) return
        val rx = px - ox
        val ry = py - oy
        val rz = pz
        val c = cos(angle)
        val s = sin(angle)
        val dot = kx * rx + ky * ry
        // Rodrigues: r cos + (k × r) sin + k (k · r)(1 - cos), with k in the sheet's plane.
        px = ox + rx * c + (ky * rz) * s + kx * dot * (1f - c)
        py = oy + ry * c + (-kx * rz) * s + ky * dot * (1f - c)
        pz = rz * c + (kx * ry - ky * rx) * s
    }

    /** The left half's fold along the middle: up towards the viewer by [angle]. */
    private fun turnAboutY(angle: Float) {
        if (angle == 0f) return
        val c = cos(angle)
        val s = sin(angle)
        val x = px * c + pz * s
        pz = -px * s + pz * c
        px = x
    }

    companion object {
        const val FACETS = 10
        const val CORNERS = 3
        const val CORNER_FLOATS = CORNERS * 2
        private const val WORLD_FLOATS = CORNERS * 3

        // Parts of a half.
        private const val FLAP_KEEL = 0
        private const val FLAP_WING = 1
        private const val WING = 2
        private const val KEEL = 3

        /** How wide the keel is at the tail; the wing's crease runs from the nose to there. */
        private const val KEEL_WIDTH = 0.42f

        /** Where the wing's crease crosses the folded corner flap, back on the flap's edge before it's folded. */
        private const val FLAP_SPLIT_Y = 1f - KEEL_WIDTH / 2f

        /** The left half, nose at (0, 1): corner flap (keel side, wing side), wing (outer, inner), keel. */
        private val LEFT = listOf(
            floatArrayOf(0f, 1f, -1f, 1f, -1f, FLAP_SPLIT_Y),
            floatArrayOf(0f, 1f, -1f, FLAP_SPLIT_Y, -1f, 0f),
            floatArrayOf(0f, 1f, -1f, 0f, -1f, -1f),
            floatArrayOf(0f, 1f, -1f, -1f, -KEEL_WIDTH, -1f),
            floatArrayOf(0f, 1f, -KEEL_WIDTH, -1f, 0f, -1f),
        )
        private val PARTS = intArrayOf(FLAP_KEEL, FLAP_WING, WING, WING, KEEL)

        /** The corner's crease: from the nose to the middle of the side. */
        private val CORNER_AXIS_X = -1f / sqrt(2f)
        private val CORNER_AXIS_Y = -1f / sqrt(2f)

        /** The wing's crease: from the nose to the keel's edge at the tail. */
        private val WING_AXIS_LENGTH = sqrt(KEEL_WIDTH * KEEL_WIDTH + 4f)
        private val WING_AXIS_X = -KEEL_WIDTH / WING_AXIS_LENGTH
        private val WING_AXIS_Y = -2f / WING_AXIS_LENGTH

        /** Folded flaps spring back a little, as paper does. */
        private const val FLAP_SPRING = 0.06

        /** The keel's halves stand this far up from flat; the wings come back down to a slight V. */
        private const val HALF_ANGLE = (PI * 0.4).toFloat()
        private const val DIHEDRAL = 0.16f

        // The folds overlap a little, as hands fold paper.
        private const val CORNERS_END = 0.42f
        private const val HALF_START = 0.34f
        private const val HALF_END = 0.74f
        private const val WINGS_START = 0.62f

        /** The camera's distance, in half-sheets: a slight perspective. */
        private const val CAMERA = 7f

        /** Light from the top left, in front: normalized. */
        private const val LIGHT_X = -0.37f
        private const val LIGHT_Y = 0.56f
        private const val LIGHT_Z = 0.74f
        private const val EPSILON = 1e-6f

        private fun cross2(facet: Int, points: FloatArray): Float {
            val o = facet * CORNER_FLOATS
            val ax = points[o + 2] - points[o]
            val ay = points[o + 3] - points[o + 1]
            val bx = points[o + 4] - points[o]
            val by = points[o + 5] - points[o + 1]
            return ax * by - ay * bx
        }
    }
}

/** One folded triangle as [planeFacets] gives it: corners in 3D, before turning. For tests and checks. */
internal data class Facet(val corners: List<Triple<Float, Float, Float>>) {
    /** Its area: folding moves paper, it doesn't stretch it. */
    val area: Float
        get() {
            val (a, b, c) = corners
            val ux = b.first - a.first
            val uy = b.second - a.second
            val uz = b.third - a.third
            val vx = c.first - a.first
            val vy = c.second - a.second
            val vz = c.third - a.third
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            return sqrt(nx * nx + ny * ny + nz * nz) / 2f
        }
}

/** The sheet folded [fold] of the way into a dart (see [PlaneModel]), as triangles in 3D. */
internal fun planeFacets(fold: Float): List<Facet> {
    val model = PlaneModel()
    model.fold(fold)
    val folded = model.folded
    return List(PlaneModel.FACETS) { facet ->
        Facet(
            List(PlaneModel.CORNERS) { c ->
                val w = (facet * PlaneModel.CORNERS + c) * 3
                Triple(folded[w], folded[w + 1], folded[w + 2])
            },
        )
    }
}

/** 0 below [from], 1 above [to], and smoothly between. */
internal fun smoothstep(from: Float, to: Float, value: Float): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The airplane's way out of the card (docs/PLAN.md §3.3): a cubic curve from the middle, rising gently as it
 * launches, then up and away past the top right corner. Points in a [width] by [height] card, y down.
 */
internal class FlightPath(width: Float, height: Float) {
    private val x0 = width / 2f
    private val y0 = height / 2f
    private val x1 = width * C1_X
    private val y1 = height * C1_Y
    private val x2 = width * C2_X
    private val y2 = height * C2_Y
    private val x3 = width + height * END_PAST
    private val y3 = -height * END_PAST

    /** Where it is at [t], from 0 to 1. */
    fun x(t: Float) = cubic(x0, x1, x2, x3, t)

    fun y(t: Float) = cubic(y0, y1, y2, y3, t)

    /**
     * Which way it heads at [t], as [PlaneModel.pose] takes it: counterclockwise from up, in radians.
     */
    fun heading(t: Float): Float {
        val dx = slope(x0, x1, x2, x3, t)
        val dy = slope(y0, y1, y2, y3, t)
        if (abs(dx) + abs(dy) < MIN_SLOPE) return heading(MIN_SLOPE_T)
        // Screen y is down: up is -dy.
        return atan2(-dx, -dy)
    }

    private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }

    private fun slope(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * (b - a) + 6f * u * t * (c - b) + 3f * t * t * (d - c)
    }

    private companion object {
        const val C1_X = 0.74f
        const val C1_Y = 0.5f
        const val C2_X = 0.9f
        const val C2_Y = 0.32f

        /** Ends this many card heights past the top right corner: out of sight however big it is then. */
        const val END_PAST = 0.6f
        const val MIN_SLOPE = 1e-3f
        const val MIN_SLOPE_T = 0.01f
    }
}
