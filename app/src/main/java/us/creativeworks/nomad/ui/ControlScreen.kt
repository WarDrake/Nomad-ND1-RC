package us.creativeworks.nomad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.creativeworks.nomad.control.ConnectionState
import us.creativeworks.nomad.control.Protocol
import us.creativeworks.nomad.input.ControllerProfile
import us.creativeworks.nomad.video.VideoPlayer
import kotlin.math.roundToInt

@Composable
fun ControlScreen(vm: ControlViewModel) {
    val status by vm.client.status.collectAsStateWithLifecycle()
    // Toggle state lives in the ViewModel so the gamepad and these controls stay in sync.
    val cameraOn = vm.cameraOn
    val upperLed = vm.upperLedOn
    val lowerLed = vm.lowerLedOn

    // throttle: -SPEED..+SPEED (negative = reverse); steering: 0..STEER_TOTAL_STEP
    var throttle by remember { mutableFloatStateOf(0f) }
    var steer by remember { mutableFloatStateOf(Protocol.STEER_TOTAL_STEP / 2f) }

    fun pushDrive() {
        val t = throttle.roundToInt()
        val steerLevel = (Protocol.STEER_CENTER_DEFAULT - Protocol.STEER_TOTAL_STEP / 2) + steer.roundToInt()
        vm.client.setDrive(
            forward = if (t > 0) t else 0,
            backward = if (t < 0) -t else 0,
            steer = steerLevel,
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraOn) {
            VideoPlayer(Modifier.fillMaxSize())
        }

        // Top status bar
        StatusBar(
            state = status.connection,
            battery = status.battery,
            firmware = status.firmware,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
        )

        // Bottom control row
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Steering", color = Color.White)
            Slider(
                value = steer,
                onValueChange = { steer = it; pushDrive() },
                onValueChangeFinished = { steer = Protocol.STEER_TOTAL_STEP / 2f; pushDrive() },
                valueRange = 0f..Protocol.STEER_TOTAL_STEP.toFloat(),
                modifier = Modifier.fillMaxWidth(0.8f),
            )

            Text("Throttle (${throttle.roundToInt()})", color = Color.White)
            Slider(
                value = throttle,
                onValueChange = { throttle = it; pushDrive() },
                onValueChangeFinished = { throttle = 0f; pushDrive() },
                valueRange = -Protocol.SPEED_TOTAL_STEP.toFloat()..Protocol.SPEED_TOTAL_STEP.toFloat(),
                modifier = Modifier.fillMaxWidth(0.8f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val connected = status.connection == ConnectionState.CONNECTED
                Button(onClick = { vm.toggleConnection() }) {
                    Text(if (connected) "Disconnect" else "Connect")
                }
                Button(onClick = { vm.toggleCamera() }, enabled = connected) {
                    Text(if (cameraOn) "Cam Off" else "Cam On")
                }
                FilterChip(
                    selected = upperLed,
                    onClick = { vm.toggleUpperLed() },
                    label = { Text("Upper LED") },
                    enabled = connected,
                )
                FilterChip(
                    selected = lowerLed,
                    onClick = { vm.toggleLowerLed() },
                    label = { Text("Lower LED") },
                    enabled = connected,
                )
            }

            // Controller profile selector
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Controller:", color = Color.White)
                ControllerProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = vm.controllerProfile == profile,
                        onClick = { vm.selectControllerProfile(profile) },
                        label = { Text(profile.label) },
                    )
                }
            }

            Text(vm.controllerProfile.hint, color = Color(0xFF888888))
        }
    }
}

@Composable
private fun StatusBar(
    state: ConnectionState,
    battery: Int?,
    firmware: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Color(0xAA000000)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (label, color) = when (state) {
            ConnectionState.CONNECTED -> "CONNECTED" to Color(0xFF33FF66)
            ConnectionState.CONNECTING -> "CONNECTING…" to Color.Yellow
            ConnectionState.FAILED -> "FAILED / LOST" to Color(0xFFFF5555)
            ConnectionState.DISCONNECTED -> "DISCONNECTED" to Color.LightGray
        }
        Text(label, color = color, fontFamily = FontFamily.Monospace)
        Text("BATT: ${battery?.toString() ?: "--"}", color = Color.White, fontFamily = FontFamily.Monospace)
        Text("FW: ${firmware ?: "--"}", color = Color.White, fontFamily = FontFamily.Monospace)
    }
}
