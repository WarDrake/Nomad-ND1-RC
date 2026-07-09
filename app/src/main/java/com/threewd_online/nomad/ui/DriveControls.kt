package com.threewd_online.nomad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.threewd_online.nomad.ui.theme.CutCornerHudShape
import com.threewd_online.nomad.ui.theme.NomadColors
import com.threewd_online.nomad.ui.theme.NomadType
import kotlin.math.abs

enum class PadAxis { HORIZONTAL, VERTICAL }

/**
 * A self-centering HUD control pad — the app's signature control surface.
 *
 * Touch anywhere in the pad to set the value by finger position along [axis];
 * release to spring back to neutral (0). Reports a normalized value in -1..1
 * (for HORIZONTAL: left..right; for VERTICAL: down..up).
 *
 * [reverseColor] tints the negative half (e.g. crimson for reverse throttle);
 * defaults to [forwardColor] for controls with no direction distinction.
 */
@Composable
fun AxisPad(
    axis: PadAxis,
    label: String,
    modifier: Modifier = Modifier,
    forwardColor: Color = NomadColors.Cyan,
    reverseColor: Color = forwardColor,
    onValue: (Float) -> Unit,
) {
    val shape = remember { CutCornerHudShape(14.dp) }
    val knob = remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .clip(shape)
            .background(NomadColors.Panel.copy(alpha = 0.5f))
            .border(1.5.dp, NomadColors.CyanDim, shape)
            .pointerInput(axis) {
                awaitEachGesture {
                    fun applyPos(pos: Offset) {
                        val v = when (axis) {
                            PadAxis.HORIZONTAL -> (pos.x / size.width) * 2f - 1f
                            PadAxis.VERTICAL -> -((pos.y / size.height) * 2f - 1f)
                        }.coerceIn(-1f, 1f)
                        knob.floatValue = v
                        onValue(v)
                    }

                    val down = awaitFirstDown(requireUnconsumed = false)
                    applyPos(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        applyPos(change.position)
                        change.consume()
                    }
                    knob.floatValue = 0f
                    onValue(0f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            drawAxisGauge(axis, knob.floatValue, forwardColor, reverseColor)
        }
        androidx.compose.material3.Text(
            text = label.uppercase(),
            style = NomadType.Label,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
    }
}

private fun DrawScope.drawAxisGauge(
    axis: PadAxis,
    value: Float,
    forwardColor: Color,
    reverseColor: Color,
) {
    val horizontal = axis == PadAxis.HORIZONTAL
    val len = if (horizontal) size.width else size.height
    val cross = if (horizontal) size.height else size.width
    val travel = len / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val accent = if (value < 0f) reverseColor else forwardColor
    val line = NomadColors.CyanDim

    // Baseline track along the axis.
    val trackStart = if (horizontal) Offset(0f, center.y) else Offset(center.x, 0f)
    val trackEnd = if (horizontal) Offset(size.width, center.y) else Offset(center.x, size.height)
    drawLine(line, trackStart, trackEnd, strokeWidth = 2f)

    // Tick marks.
    val ticks = 8
    for (i in 0..ticks) {
        val t = i / ticks.toFloat()
        val pos = t * len
        val tickHalf = if (i == ticks / 2) cross * 0.28f else cross * 0.14f
        if (horizontal) {
            drawLine(line, Offset(pos, center.y - tickHalf), Offset(pos, center.y + tickHalf), strokeWidth = 1.5f)
        } else {
            drawLine(line, Offset(center.x - tickHalf, pos), Offset(center.x + tickHalf, pos), strokeWidth = 1.5f)
        }
    }

    // Knob position along the axis.
    val knobPos = if (horizontal) {
        Offset(center.x + value * travel, center.y)
    } else {
        Offset(center.x, center.y - value * travel)
    }

    // Filled bar from center to knob (shows magnitude/direction).
    drawLine(
        accent.copy(alpha = 0.85f),
        center,
        knobPos,
        strokeWidth = (cross * 0.5f).coerceAtMost(46f),
        cap = StrokeCap.Round,
    )

    // Knob marker.
    val knobRadius = (cross * 0.30f).coerceAtMost(30f)
    drawCircle(NomadColors.Void, radius = knobRadius, center = knobPos)
    drawCircle(accent, radius = knobRadius, center = knobPos, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    if (abs(value) > 0.02f) {
        drawCircle(accent, radius = knobRadius * 0.45f, center = knobPos)
    }
}

/** Steering: horizontal, cyan both directions. */
@Composable
fun SteeringPad(modifier: Modifier = Modifier, onValue: (Float) -> Unit) =
    AxisPad(PadAxis.HORIZONTAL, "Steering", modifier, onValue = onValue)

/** Throttle: vertical, cyan forward (up) / crimson reverse (down). */
@Composable
fun ThrottlePad(modifier: Modifier = Modifier, onValue: (Float) -> Unit) =
    AxisPad(
        PadAxis.VERTICAL,
        "Throttle",
        modifier,
        forwardColor = NomadColors.Cyan,
        reverseColor = NomadColors.Crimson,
        onValue = onValue,
    )
