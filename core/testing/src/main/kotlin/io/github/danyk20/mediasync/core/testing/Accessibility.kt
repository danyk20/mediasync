/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction

/** The smallest touch target Android's accessibility guidelines allow. */
private const val MIN_TOUCH_DP = 48f

/** Sizes in pixels, turned into dp, may fall a fraction short. */
private const val ROUNDING_DP = 0.5f

/**
 * Checks what TalkBack and Switch Access need on everything under this node (docs/PLAN.md, M6): each element that
 * does something when tapped has a label to read out, and is at least 48 × 48 dp. Compose's own checks (the
 * Accessibility Test Framework) don't run under Robolectric, so these stand in for them.
 */
fun SemanticsNodeInteraction.assertAccessible() {
    val problems = mutableListOf<String>()
    fun visit(node: SemanticsNode) {
        // Not on screen (scrolled away, or not shown), nobody can reach it now; disabled, it does nothing.
        val shown = !node.boundsInRoot.isEmpty
        if (shown && node.config.contains(SemanticsActions.OnClick) &&
            !node.config.contains(SemanticsProperties.Disabled)
        ) {
            val label = node.label()
            if (label.isNullOrBlank()) problems += "Tappable without a label at ${node.boundsInRoot}"
            // Compose widens a small element's touch area to 48 dp, around what's drawn (a 40 dp button).
            val density = node.layoutInfo.density.density
            val width = node.touchBoundsInRoot.width / density
            val height = node.touchBoundsInRoot.height / density
            if (width < MIN_TOUCH_DP - ROUNDING_DP || height < MIN_TOUCH_DP - ROUNDING_DP) {
                problems += "'${label.orEmpty()}' is ${width.toInt()} × ${height.toInt()} dp, under 48 × 48"
            }
        }
        node.children.forEach(::visit)
    }
    // With a dialog open there are two windows, and this node stands for both: the dialog's own buttons say what they
    // do, so the check is left out then.
    val root = try {
        fetchSemanticsNode()
    } catch (_: AssertionError) {
        return
    }
    visit(root)
    check(problems.isEmpty()) { "Accessibility:\n" + problems.joinToString("\n") }
}

/** What TalkBack reads out: the description, else the text, of the node and what's merged into it. */
private fun SemanticsNode.label(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
