/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.R
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A transfer's speed [atMillis] after it started, as the speed chart plots it. */
data class SpeedPoint(val atMillis: Long, val bytesPerSecond: Long)

/** How far one second reaches across the chart: about a minute fits on a phone. */
private val SECOND_WIDTH = 6.dp
private val CHART_HEIGHT = 200.dp
private val AXIS_GUTTER = 56.dp
private val TIME_GUTTER = 20.dp
private const val SPEED_STEPS = 4
private const val AREA_ALPHA = 0.1f
private const val MILLIS_PER_SECOND = 1_000L

/**
 * The speed over a transfer, one point a second, with the speed axis fixed on the left. It shows the latest minute or
 * so and follows new points; dragging sideways goes back in time (and following resumes at the end). Tapping the line
 * shows the speed at that moment.
 */
@Composable
fun SpeedChart(points: List<SpeedPoint>, modifier: Modifier = Modifier) {
    val ink = ChartInk(
        colors = MaterialTheme.colorScheme,
        textMeasurer = rememberTextMeasurer(),
        labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        speedLabel = stringResource(R.string.transfer_speed),
    )
    val summary = speedSummary(points)
    // The end of the window shown; `null` follows the newest point.
    var shownEnd by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf<SpeedPoint?>(null) }
    val latest = points.lastOrNull()?.atMillis ?: 0L
    val top = speedAxisTop(points.maxOfOrNull { it.bytesPerSecond } ?: 0L)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .testTag(SPEED_CHART_TAG)
            .semantics { contentDescription = summary }
            .pointerInput(latest) {
                val millisPerPixel = MILLIS_PER_SECOND / SECOND_WIDTH.toPx()
                detectHorizontalDragGestures { _, dragAmount ->
                    val span = ((size.width - AXIS_GUTTER.toPx()) * millisPerPixel).toLong()
                    val end = (shownEnd ?: latest) - (dragAmount * millisPerPixel).toLong()
                    shownEnd = end.coerceIn(minOf(span, latest), latest).takeIf { it < latest }
                    selected = null
                }
            }
            .pointerInput(points, shownEnd) {
                detectTapGestures { offset ->
                    val window = Window(size.width.toFloat(), AXIS_GUTTER.toPx(), SECOND_WIDTH.toPx(), shownEnd, latest)
                    val at = window.timeAt(offset.x)
                    selected = points.minByOrNull { kotlin.math.abs(it.atMillis - at) }
                }
            },
    ) {
        val window = Window(size.width, AXIS_GUTTER.toPx(), SECOND_WIDTH.toPx(), shownEnd, latest)
        val plot = Plot(window, bottom = size.height - TIME_GUTTER.toPx(), top = top)
        drawSpeedAxis(plot, ink)
        clipRect(left = window.left) {
            drawTimeAxis(plot, ink)
            drawSpeedLine(plot, points, live = shownEnd == null, ink.colors)
            selected?.let { drawSelected(plot, it, ink) }
        }
    }
}

/** What the chart draws with: the theme's colours, and its labels' text. */
private class ChartInk(
    val colors: ColorScheme,
    val textMeasurer: TextMeasurer,
    val labelStyle: TextStyle,
    private val speedLabel: String, // "%1$s/s"
) {
    fun speed(size: String): String = speedLabel.format(size)
}

/** The [window] of time across, and speeds from 0 at [bottom] up to [top] at the top edge. */
private class Plot(val window: Window, val bottom: Float, val top: Long) {
    fun y(bytesPerSecond: Long): Float = bottom - bytesPerSecond.toFloat() / top * bottom
}

/** Hairline gridlines at the speed axis's clean steps, labelled in the fixed gutter on the left. */
private fun DrawScope.drawSpeedAxis(plot: Plot, ink: ChartInk) {
    for (step in 0..SPEED_STEPS) {
        val value = plot.top / SPEED_STEPS * step
        val lineY = plot.y(value)
        drawLine(
            ink.colors.outlineVariant,
            Offset(plot.window.left, lineY),
            Offset(size.width, lineY),
            strokeWidth = 1f,
        )
        val label = ink.textMeasurer.measure(if (value == 0L) "0" else ink.speed(speedTick(value)), ink.labelStyle)
        drawText(label, topLeft = Offset(0f, (lineY - label.size.height / 2f).coerceIn(0f, plot.bottom)))
    }
}

/** Time labels along the bottom, at a step that fits about four of them. */
private fun DrawScope.drawTimeAxis(plot: Plot, ink: ChartInk) {
    val window = plot.window
    val step = timeStep(window.end - window.start)
    var tick = (window.start + step - 1) / step * step // The first one inside the window.
    while (tick <= window.end) {
        if (tick >= 0) {
            val x = window.x(tick)
            drawLine(
                ink.colors.outlineVariant,
                Offset(x, plot.bottom),
                Offset(x, plot.bottom + 4.dp.toPx()),
                strokeWidth = 1f,
            )
            val label = ink.textMeasurer.measure(elapsed(tick), ink.labelStyle)
            val labelLeft = (x - label.size.width / 2f).coerceIn(window.left, size.width - label.size.width)
            drawText(label, topLeft = Offset(labelLeft, plot.bottom + 4.dp.toPx()))
        }
        tick += step
    }
}

/** The speed as a line over a faint area, with a dot on the newest point while the chart follows it. */
private fun DrawScope.drawSpeedLine(plot: Plot, points: List<SpeedPoint>, live: Boolean, colors: ColorScheme) {
    val window = plot.window
    val visible = points.filter { it.atMillis in window.start - MILLIS_PER_SECOND..window.end + MILLIS_PER_SECOND }
    if (visible.isEmpty()) return
    val line = Path().apply {
        visible.forEachIndexed { i, point ->
            val x = window.x(point.atMillis)
            if (i == 0) moveTo(x, plot.y(point.bytesPerSecond)) else lineTo(x, plot.y(point.bytesPerSecond))
        }
    }
    val area = Path().apply {
        addPath(line)
        lineTo(window.x(visible.last().atMillis), plot.bottom)
        lineTo(window.x(visible.first().atMillis), plot.bottom)
        close()
    }
    drawPath(area, colors.primary.copy(alpha = AREA_ALPHA))
    drawPath(line, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (live) {
        val newest = points.last()
        val center = Offset(window.x(newest.atMillis), plot.y(newest.bytesPerSecond))
        drawCircle(colors.surface, radius = 6.dp.toPx(), center = center)
        drawCircle(colors.primary, radius = 4.dp.toPx(), center = center)
    }
}

/** A crosshair on the tapped [point], with its time and speed in a box at the top. */
private fun DrawScope.drawSelected(plot: Plot, point: SpeedPoint, ink: ChartInk) {
    val colors = ink.colors
    val x = plot.window.x(point.atMillis)
    drawLine(colors.onSurfaceVariant, Offset(x, 0f), Offset(x, plot.bottom), strokeWidth = 1f)
    drawCircle(colors.primary, radius = 4.dp.toPx(), center = Offset(x, plot.y(point.bytesPerSecond)))
    val text = ink.textMeasurer.measure(
        "${elapsed(point.atMillis)} · ${ink.speed(formatBytes(point.bytesPerSecond))}",
        ink.labelStyle.copy(color = colors.onSurface),
    )
    val boxWidth = text.size.width + 12.dp.toPx()
    val boxLeft = (x - boxWidth / 2).coerceIn(plot.window.left, size.width - boxWidth)
    drawRoundRect(
        colors.surfaceVariant,
        topLeft = Offset(boxLeft, 0f),
        size = Size(boxWidth, text.size.height + 8.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawText(text, topLeft = Offset(boxLeft + 6.dp.toPx(), 4.dp.toPx()))
}

/**
 * The part of the transfer on screen: from [start] to [end] millis after it started, drawn right of [left]. It ends
 * at [shownEnd], or follows [latest]; a transfer shorter than the window starts at the left edge.
 */
private class Window(width: Float, val left: Float, private val pxPerSecond: Float, shownEnd: Long?, latest: Long) {
    private val span = ((width - left) / pxPerSecond * MILLIS_PER_SECOND).toLong()
    val end: Long = maxOf(shownEnd ?: latest, span)
    val start: Long = end - span

    fun x(atMillis: Long): Float = left + (atMillis - start).toFloat() / MILLIS_PER_SECOND * pxPerSecond

    fun timeAt(x: Float): Long = start + ((x - left) / pxPerSecond * MILLIS_PER_SECOND).toLong()
}

/** The chart's top speed: four clean steps (1, 2, 2.5 or 5 × 10ⁿ) that reach past the peak. At least 1 MB/s. */
internal fun speedAxisTop(peak: Long): Long {
    val rawStep = maxOf(peak, MIN_TOP) / SPEED_STEPS.toDouble()
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= rawStep } * magnitude
    return (step * SPEED_STEPS).toLong()
}

private const val MIN_TOP = 1_000_000L

/** A tick's size without a needless decimal: "2 MB", "2.5 MB". */
internal fun speedTick(bytesPerSecond: Long): String =
    if (bytesPerSecond == 0L) "0" else formatBytes(bytesPerSecond).replace(Regex("[.,]0 "), " ")

/** Time since the transfer started: "1:42", or "1:02:05" from an hour on. */
internal fun elapsed(atMillis: Long): String {
    val seconds = atMillis / MILLIS_PER_SECOND
    val (h, m, s) = Triple(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    return if (h >
        0
    ) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/** A step between time labels that fits about four in [spanMillis]. */
internal fun timeStep(spanMillis: Long): Long =
    TIME_STEPS.firstOrNull { it * TIME_LABELS >= spanMillis } ?: TIME_STEPS.last()

private const val TIME_LABELS = 4
private val TIME_STEPS = listOf(10, 15, 30, 60, 120, 300, 600, 1_800, 3_600).map { it * MILLIS_PER_SECOND }

/** What TalkBack reads for the chart: how long, and the speeds now, on average and at the peak. */
@Composable
private fun speedSummary(points: List<SpeedPoint>): String {
    if (points.isEmpty()) return stringResource(R.string.speed_chart_waiting)
    val stats = SpeedStats.of(points)
    val minutes = (points.last().atMillis / MILLIS_PER_SECOND / 60).toInt()
    val duration = if (minutes > 0) {
        stringResource(R.string.speed_chart_minutes, minutes)
    } else {
        stringResource(R.string.speed_chart_seconds, (points.last().atMillis / MILLIS_PER_SECOND).toInt())
    }
    val pattern = stringResource(R.string.transfer_speed)
    fun speed(bytesPerSecond: Long) = pattern.format(formatBytes(bytesPerSecond))
    return stringResource(
        R.string.speed_chart_summary,
        duration,
        speed(stats.now),
        speed(stats.average),
        speed(stats.peak),
    )
}

/** The speed now, on average and at the peak, over [SpeedPoint]s. */
data class SpeedStats(val now: Long, val average: Long, val peak: Long) {
    companion object {
        fun of(points: List<SpeedPoint>) = SpeedStats(
            now = points.lastOrNull()?.bytesPerSecond ?: 0L,
            average = if (points.isEmpty()) 0L else points.sumOf { it.bytesPerSecond } / points.size,
            peak = points.maxOfOrNull { it.bytesPerSecond } ?: 0L,
        )
    }
}

/** For tests: finds the chart. */
const val SPEED_CHART_TAG = "speed-chart"
