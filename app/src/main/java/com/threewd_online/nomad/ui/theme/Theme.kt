package com.threewd_online.nomad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NomadColorScheme = darkColorScheme(
    primary = NomadColors.Cyan,
    onPrimary = NomadColors.Void,
    secondary = NomadColors.Cyan,
    error = NomadColors.Crimson,
    background = NomadColors.Void,
    onBackground = NomadColors.TextHi,
    surface = NomadColors.Panel,
    onSurface = NomadColors.TextHi,
)

@Composable
fun NomadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NomadColorScheme,
        typography = NomadTypography,
        content = content,
    )
}
