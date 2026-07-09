package com.threewd_online.nomad.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette derived from the actual PDP "Mass Effect: Andromeda — Nomad ND1"
 * product: the in-game control HUD (dark teal hex field, bright cyan "active"
 * highlight, white iconography) and the packaging's crimson NOMAD ND1 band.
 */
object NomadColors {
    val Void = Color(0xFF04100E)        // base background — dark teal-black
    val Panel = Color(0xFF0A1A18)       // HUD panel fill (used semi-transparent over video)
    val PanelBorder = Color(0xFF14403B) // subtle frame lines
    val Cyan = Color(0xFF19B6D0)        // primary interactive accent (from the "on" HUD icon)
    val CyanDim = Color(0xFF0C4A55)     // inactive tracks / borders
    val Crimson = Color(0xFFD42A46)     // alerts, disconnected/failed, reverse throttle
    val Amber = Color(0xFFE8A33D)       // battery-low warning only (used sparingly)
    val TextHi = Color(0xFFE9F5F4)      // primary text
    val TextLo = Color(0xFF6C8582)      // muted labels
    val Grid = Color(0xFF0C2420)        // hex/vignette backdrop lines
}
