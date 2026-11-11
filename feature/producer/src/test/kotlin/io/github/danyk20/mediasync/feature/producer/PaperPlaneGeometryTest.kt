/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.producer

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Test

/** The sheet folds into a dart, rigidly (docs/PLAN.md §10.2). */
class PaperPlaneGeometryTest {

    @Test
    fun flatTheTrianglesMakeUpTheSheet() {
        val flat = planeFacets(0f)

        flat.size shouldBe PlaneModel.FACETS
        flat.sumOf { it.area.toDouble() }.toFloat() shouldBe (4f plusOrMinus 0.001f)
        flat.flatMap { it.corners }.all { it.third == 0f } shouldBe true
    }

    @Test
    fun foldingMovesThePaperWithoutStretchingIt() {
        val flat = planeFacets(0f).map { it.area }
        for (fold in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f)) {
            val folded = planeFacets(fold)
            folded.flatMap { it.corners }.all { (x, y, z) -> x.isFinite() && y.isFinite() && z.isFinite() } shouldBe
                true
            folded.map { it.area }.zip(flat).forEach { (area, before) -> area shouldBe (before plusOrMinus 1e-4f) }
            // Nothing leaves the sheet's reach.
            folded.flatMap {
                it.corners
            }.all { (x, y, z) -> abs(x) <= 1.1f && abs(y) <= 1.1f && abs(z) <= 1.1f } shouldBe
                true
        }
    }

    @Test
    fun foldedItIsADart() {
        val dart = planeFacets(1f).flatMap { it.corners }

        // The nose stays put, and the corners came in: narrower than the sheet, as long.
        dart.maxOf { it.second } shouldBe (1f plusOrMinus 0.001f)
        dart.minOf { it.second } shouldBe (-1f plusOrMinus 0.1f)
        val span = dart.maxOf { it.first } - dart.minOf { it.first }
        span shouldBeLessThan 2f
        span shouldBeGreaterThan 1f // Wings out.
        // The keel hangs below the wings, symmetric.
        dart.maxOf { it.third } shouldBeGreaterThan 0.3f
        (dart.sumOf { it.first.toDouble() }.toFloat()) shouldBe (0f plusOrMinus 0.001f)
    }

    @Test
    fun halfwayTheCornersAreFoldedAndTheSheetNotYetHalved() {
        val model = PlaneModel()
        model.fold(0.4f)
        model.pose(heading = 0f, pitch = 0f, roll = 0f)

        // The flaps show their back; the rest, the photo.
        model.facing.count { !it } shouldBe 4
        model.order.toSet() shouldBe (0 until PlaneModel.FACETS).toSet()
    }

    @Test
    fun theFlightLeavesTheCardHeadingUpAndRight() {
        val path = FlightPath(width = 400f, height = 200f)

        path.x(0f) shouldBe 200f
        path.y(0f) shouldBe 100f
        path.x(1f) shouldBeGreaterThan 400f
        path.y(1f) shouldBeLessThan 0f
        // Counterclockwise from up: to the right is negative.
        val start = path.heading(0f)
        start shouldBeLessThan 0f
        start shouldBeGreaterThan (-PI).toFloat()
        path.heading(1f) shouldBeGreaterThan start
    }

    @Test
    fun smoothstepEasesBetweenItsEdges() {
        smoothstep(0.2f, 0.6f, 0f) shouldBe 0f
        smoothstep(0.2f, 0.6f, 0.4f) shouldBe (0.5f plusOrMinus 1e-6f)
        smoothstep(0.2f, 0.6f, 1f) shouldBe 1f
    }
}
