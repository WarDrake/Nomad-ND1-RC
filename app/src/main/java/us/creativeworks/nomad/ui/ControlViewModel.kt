package us.creativeworks.nomad.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import us.creativeworks.nomad.control.ConnectionState
import us.creativeworks.nomad.control.NomadClient
import us.creativeworks.nomad.control.Protocol
import us.creativeworks.nomad.net.CarWifiBinder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wires the Wi-Fi binder to the control client and holds shared UI toggle state
 * so both the on-screen controls and the gamepad drive the same source of truth.
 */
class ControlViewModel(app: Application) : AndroidViewModel(app) {

    private val wifi = CarWifiBinder(app)
    val client = NomadClient(bindSocket = { socket -> wifi.bindSocket(socket) })

    // Shared toggle state (observed by Compose, mutated by UI *and* gamepad).
    var cameraOn by mutableStateOf(false)
        private set
    var upperLedOn by mutableStateOf(false)
        private set
    var lowerLedOn by mutableStateOf(false)
        private set

    init {
        // Start listening for the car's Wi-Fi network immediately.
        wifi.request(onAvailable = { /* available; connect() will bind on demand */ })
    }

    fun connect() = client.connect()
    fun disconnect() = client.disconnect()

    fun toggleConnection() {
        if (client.status.value.connection == ConnectionState.CONNECTED) disconnect() else connect()
    }

    fun toggleCamera() {
        cameraOn = !cameraOn
    }

    fun toggleUpperLed() {
        upperLedOn = !upperLedOn
        client.upperLed(upperLedOn)
    }

    fun toggleLowerLed() {
        lowerLedOn = !lowerLedOn
        client.lowerLed(lowerLedOn)
    }

    /**
     * Apply a gamepad drive intent. Inputs are normalized:
     *   [steer] in -1..1 (left..right), [forward]/[reverse] in 0..1.
     * Converts to protocol units, honoring the current steering trim.
     */
    fun onGamepadDrive(steer: Float, forward: Float, reverse: Float) {
        val s = if (abs(steer) < STEER_DEADZONE) 0f else steer
        val steerLevel = client.steerCenter + (s * (Protocol.STEER_TOTAL_STEP / 2)).roundToInt()
        val net = forward - reverse
        val fw = if (net > 0f) (net * Protocol.SPEED_TOTAL_STEP).roundToInt() else 0
        val bw = if (net < 0f) (-net * Protocol.SPEED_TOTAL_STEP).roundToInt() else 0
        client.setDrive(fw, bw, steerLevel)
    }

    override fun onCleared() {
        client.shutdown()
        wifi.release()
    }

    private companion object {
        const val STEER_DEADZONE = 0.12f
    }
}
