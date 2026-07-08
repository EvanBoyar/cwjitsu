package com.cwjitsu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sliders that ONLY respond to a deliberate horizontal drag.
 *
 * Material3's Slider moves the thumb to wherever a finger lands on the
 * track, so scrolling a settings page over a slider changes its value.
 * These variants are built on [draggable] with [Orientation.Horizontal]:
 * the gesture is claimed only after horizontal movement beats the scroll
 * container's vertical slop, a vertical swipe scrolls the page untouched,
 * and a bare tap does nothing.
 *
 * [steps] follows the Material Slider convention: the number of
 * INTERMEDIATE snap points between the range ends (0 = continuous).
 */

private const val TRACK_WIDTH_DP = 4
private const val THUMB_RADIUS_DP = 10

private fun snapToStep(v: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val clamped = v.coerceIn(range.start, range.endInclusive)
    if (steps <= 0) return clamped
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val tick = span / (steps + 1)
    return range.start + ((clamped - range.start) / tick).roundToInt() * tick
}

private fun fractionOf(v: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else ((v - range.start) / span).coerceIn(0f, 1f)
}

@Composable
fun DragOnlySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    // Unsnapped accumulator so slow drags on a coarse-stepped slider still
    // cross tick boundaries instead of resetting against the snapped value.
    var dragValue by remember { mutableFloatStateOf(value) }
    val span = valueRange.endInclusive - valueRange.start
    val fraction = fractionOf(value, valueRange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { dragValue = value },
                state = rememberDraggableState { delta ->
                    if (widthPx > 0f && span > 0f) {
                        dragValue = (dragValue + delta / widthPx * span)
                            .coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(snapToStep(dragValue, valueRange, steps))
                    }
                },
            ),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = TRACK_WIDTH_DP.dp.toPx()
            val r = THUMB_RADIUS_DP.dp.toPx()
            val cy = size.height / 2f
            val usable = size.width - 2 * r
            drawLine(
                trackColor.copy(alpha = 0.25f),
                Offset(r, cy), Offset(r + usable, cy), stroke, StrokeCap.Round,
            )
            val tx = r + usable * fraction
            drawLine(trackColor, Offset(r, cy), Offset(tx, cy), stroke, StrokeCap.Round)
            drawCircle(thumbColor, r, Offset(tx, cy))
        }
    }
}

/**
 * Two-thumb variant. The drag adjusts whichever thumb was nearest where
 * the gesture started, clamped at the other thumb so start <= end always
 * holds.
 */
@Composable
fun DragOnlyRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary,
) {
    val thumbRadiusPx = with(LocalDensity.current) { THUMB_RADIUS_DP.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var draggingEnd by remember { mutableStateOf(false) }
    val span = valueRange.endInclusive - valueRange.start
    val startFraction = fractionOf(value.start, valueRange)
    val endFraction = fractionOf(value.endInclusive, valueRange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { pos ->
                    val usable = (widthPx - 2 * thumbRadiusPx).coerceAtLeast(1f)
                    val startX = thumbRadiusPx + usable * startFraction
                    val endX = thumbRadiusPx + usable * endFraction
                    draggingEnd = abs(pos.x - endX) < abs(pos.x - startX) ||
                        // Overlapping thumbs: dragging right must grab the
                        // end thumb or the range could never widen again.
                        (startX == endX && pos.x > endX)
                    dragValue = if (draggingEnd) value.endInclusive else value.start
                },
                state = rememberDraggableState { delta ->
                    if (widthPx > 0f && span > 0f) {
                        dragValue = (dragValue + delta / widthPx * span)
                            .coerceIn(valueRange.start, valueRange.endInclusive)
                        val snapped = snapToStep(dragValue, valueRange, steps)
                        onValueChange(
                            if (draggingEnd) {
                                value.start..snapped.coerceAtLeast(value.start)
                            } else {
                                snapped.coerceAtMost(value.endInclusive)..value.endInclusive
                            }
                        )
                    }
                },
            ),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = TRACK_WIDTH_DP.dp.toPx()
            val r = THUMB_RADIUS_DP.dp.toPx()
            val cy = size.height / 2f
            val usable = size.width - 2 * r
            val ax = r + usable * startFraction
            val bx = r + usable * endFraction
            drawLine(
                trackColor.copy(alpha = 0.25f),
                Offset(r, cy), Offset(r + usable, cy), stroke, StrokeCap.Round,
            )
            drawLine(trackColor, Offset(ax, cy), Offset(bx, cy), stroke, StrokeCap.Round)
            drawCircle(thumbColor, r, Offset(ax, cy))
            drawCircle(thumbColor, r, Offset(bx, cy))
        }
    }
}
