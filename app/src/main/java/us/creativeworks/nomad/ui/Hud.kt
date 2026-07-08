package us.creativeworks.nomad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.creativeworks.nomad.control.ConnectionState
import us.creativeworks.nomad.ui.theme.CutCornerHudShape
import us.creativeworks.nomad.ui.theme.NomadColors
import us.creativeworks.nomad.ui.theme.NomadType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Chamfered panel container for grouped HUD readouts. */
@Composable
fun HudPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = remember { CutCornerHudShape(10.dp) }
    Box(
        modifier
            .clip(shape)
            .background(NomadColors.Panel.copy(alpha = 0.72f))
            .border(1.dp, NomadColors.PanelBorder, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { content() }
}

/** Angular action button. [active] fills with the accent; [enabled] dims it. */
@Composable
fun HudActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = NomadColors.Cyan,
) {
    val shape = remember { CutCornerHudShape(6.dp) }
    val borderColor = when {
        !enabled -> NomadColors.TextLo.copy(alpha = 0.2f)
        active -> accent
        else -> NomadColors.CyanDim
    }
    val fill = if (active) accent.copy(alpha = 0.16f) else NomadColors.Panel.copy(alpha = 0.55f)
    val textColor = when {
        !enabled -> NomadColors.TextLo.copy(alpha = 0.4f)
        active -> accent
        else -> NomadColors.TextHi
    }
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.5.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .widthIn(min = 76.dp)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = NomadType.Label.copy(color = textColor, fontSize = 12.sp))
    }
}

/** Connection state + battery + firmware readout cluster. */
@Composable
fun StatusCluster(
    state: ConnectionState,
    battery: Int?,
    firmware: String?,
    modifier: Modifier = Modifier,
) {
    HudPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionChip(state)
            BatteryGauge(battery)
            Text(
                "FW ${firmware ?: "----"}",
                style = NomadType.Label.copy(fontSize = 10.sp),
            )
        }
    }
}

@Composable
private fun ConnectionChip(state: ConnectionState) {
    val (label, color) = when (state) {
        ConnectionState.CONNECTED -> "LINK ACTIVE" to NomadColors.Cyan
        ConnectionState.CONNECTING -> "LINKING" to NomadColors.Amber
        ConnectionState.FAILED -> "LINK LOST" to NomadColors.Crimson
        ConnectionState.DISCONNECTED -> "OFFLINE" to NomadColors.TextLo
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = NomadType.Title.copy(fontSize = 13.sp, color = color))
    }
}

@Composable
private fun BatteryGauge(level: Int?) {
    val frac = ((level ?: 0) / 100f).coerceIn(0f, 1f)
    val color = when {
        level == null -> NomadColors.TextLo
        frac < 0.25f -> NomadColors.Amber
        else -> NomadColors.Cyan
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("BATT", style = NomadType.Label)
        Canvas(Modifier.size(width = 68.dp, height = 12.dp)) {
            drawRect(NomadColors.CyanDim.copy(alpha = 0.4f), size = size)
            if (frac > 0f) {
                drawRect(color, size = Size(size.width * frac, size.height))
            }
            // Segment dividers for the HUD look.
            for (i in 1..4) {
                val x = size.width * i / 5f
                drawLine(NomadColors.Void, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            }
            drawRect(NomadColors.CyanDim, size = size, style = Stroke(width = 1.5f))
        }
        Text(level?.toString() ?: "--", style = NomadType.Value.copy(fontSize = 16.sp, color = color))
    }
}

/**
 * Full-screen backdrop shown when there's no live video: a dark hex vignette
 * with an Andromeda-style hexagon reticle and a context prompt.
 */
@Composable
fun NoSignalBackdrop(state: ConnectionState, modifier: Modifier = Modifier) {
    Box(modifier.background(NomadColors.Void), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(NomadColors.Grid, NomadColors.Void),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.7f,
                ),
            )
            val r = min(size.width, size.height) * 0.26f
            drawHexagon(Offset(size.width / 2f, size.height / 2f), r, NomadColors.Cyan.copy(alpha = 0.16f))
            drawHexagon(Offset(size.width / 2f, size.height / 2f), r * 0.72f, NomadColors.Cyan.copy(alpha = 0.08f))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("NOMAD ND1", style = NomadType.Title.copy(fontSize = 28.sp, letterSpacing = 10.sp))
            val prompt = when (state) {
                ConnectionState.CONNECTED -> "CAMERA OFFLINE · ENABLE CAM FOR VIDEO"
                ConnectionState.CONNECTING -> "ESTABLISHING LINK"
                else -> "NO VEHICLE LINK · CONNECT TO DRIVE"
            }
            Text(prompt, style = NomadType.Label.copy(fontSize = 12.sp))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHexagon(
    center: Offset,
    radius: Float,
    color: Color,
) {
    val path = Path()
    for (i in 0..5) {
        val angle = Math.toRadians(60.0 * i - 90.0)
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = Stroke(width = 2f))
}
