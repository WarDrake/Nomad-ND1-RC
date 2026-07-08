package us.creativeworks.nomad.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * HUD typography. No custom font is bundled (the game faces aren't licensable),
 * so personality comes from treatment: wide letter-tracking on uppercase sans
 * labels, and a monospace face for live telemetry values.
 */
object NomadType {
    /** Small tracked uppercase label ("STEERING", "BATT"). Uppercase the string yourself. */
    val Label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 2.5.sp,
        color = NomadColors.TextLo,
    )

    /** Monospace readout for numbers (battery, steering, firmware). */
    val Value = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 1.sp,
        color = NomadColors.TextHi,
    )

    /** Wordmark / section title. */
    val Title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 4.sp,
        color = NomadColors.TextHi,
    )
}

val NomadTypography = Typography(
    titleLarge = NomadType.Title,
    labelLarge = NomadType.Label,
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        color = NomadColors.TextHi,
    ),
)
