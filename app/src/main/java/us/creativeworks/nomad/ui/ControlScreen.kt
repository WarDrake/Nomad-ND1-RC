package us.creativeworks.nomad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.creativeworks.nomad.control.ConnectionState
import us.creativeworks.nomad.input.ControllerProfile
import us.creativeworks.nomad.ui.theme.NomadColors
import us.creativeworks.nomad.ui.theme.NomadType
import us.creativeworks.nomad.video.VideoPlayer

@Composable
fun ControlScreen(vm: ControlViewModel) {
    val status by vm.client.status.collectAsStateWithLifecycle()
    val connected = status.connection == ConnectionState.CONNECTED

    var showSetup by remember { mutableStateOf(false) }
    var steer by remember { mutableFloatStateOf(0f) }
    var throttle by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(NomadColors.Void)) {
        // Background layer: live video when the camera is on, else the HUD backdrop.
        if (vm.cameraOn) {
            VideoPlayer(Modifier.fillMaxSize())
        } else {
            NoSignalBackdrop(status.connection, Modifier.fillMaxSize())
        }

        // Top-left: telemetry.
        StatusCluster(
            state = status.connection,
            battery = status.battery,
            firmware = status.firmware,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )

        // Top-right: actions.
        Row(
            Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HudActionButton("Cam", { vm.toggleCamera() }, active = vm.cameraOn, enabled = connected)
            HudActionButton("Led ▲", { vm.toggleUpperLed() }, active = vm.upperLedOn, enabled = connected)
            HudActionButton("Led ▼", { vm.toggleLowerLed() }, active = vm.lowerLedOn, enabled = connected)
            HudActionButton(
                if (connected) "Disconnect" else "Connect",
                { vm.toggleConnection() },
                active = connected,
                accent = if (connected) NomadColors.Crimson else NomadColors.Cyan,
            )
            HudActionButton("Setup", { showSetup = true })
        }

        // Bottom-left: steering (left thumb).
        SteeringPad(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
                .size(width = 250.dp, height = 118.dp),
            onValue = { steer = it; vm.onTouchDrive(steer, throttle) },
        )

        // Bottom-right: throttle (right thumb).
        ThrottlePad(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(width = 118.dp, height = 210.dp),
            onValue = { throttle = it; vm.onTouchDrive(steer, throttle) },
        )

        if (showSetup) {
            SetupOverlay(vm, onClose = { showSetup = false })
        }
    }
}

@Composable
private fun SetupOverlay(vm: ControlViewModel, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000814)),
        contentAlignment = Alignment.Center,
    ) {
        HudPanel(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Controller Profile".uppercase(), style = NomadType.Title)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControllerProfile.entries.forEach { profile ->
                        HudActionButton(
                            profile.label,
                            { vm.selectControllerProfile(profile) },
                            active = vm.controllerProfile == profile,
                        )
                    }
                }
                Text(vm.controllerProfile.hint, style = NomadType.Label.copy(fontSize = 11.sp))
                HudActionButton("Close", onClose)
            }
        }
    }
}
