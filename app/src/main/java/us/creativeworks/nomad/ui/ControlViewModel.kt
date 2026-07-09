package us.creativeworks.nomad.ui

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import us.creativeworks.nomad.audio.SoundManager
import us.creativeworks.nomad.control.ConnectionState
import us.creativeworks.nomad.control.NomadClient
import us.creativeworks.nomad.control.Protocol
import us.creativeworks.nomad.input.ControllerProfile
import us.creativeworks.nomad.input.GamepadAction
import us.creativeworks.nomad.net.CarWifiBinder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Wires the Wi-Fi binder to the control client and holds shared UI toggle state
 * so both the on-screen controls and the gamepad drive the same source of truth.
 */
class ControlViewModel(app: Application) : AndroidViewModel(app) {

    private val wifi = CarWifiBinder(app)
    val client = NomadClient(bindSocket = { socket -> wifi.bindSocket(socket) })
    private val sound = SoundManager(app)

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Shared toggle state (observed by Compose, mutated by UI *and* gamepad).
    var cameraOn by mutableStateOf(false)
        private set
    var upperLedOn by mutableStateOf(false)
        private set
    var lowerLedOn by mutableStateOf(false)
        private set

    /** Active gamepad mapping, persisted across launches. */
    var controllerProfile by mutableStateOf(
        ControllerProfile.fromNameOrDefault(prefs.getString(KEY_PROFILE, null))
    )
        private set

    /** Steering center (trim), persisted. 128 = neutral; adjust to correct pull. */
    var steerCenter by mutableStateOf(prefs.getInt(KEY_TRIM, Protocol.STEER_CENTER_DEFAULT))
        private set

    /** Master toggle for engine + UI audio, persisted. */
    var soundEnabled by mutableStateOf(prefs.getBoolean(KEY_SOUND, true))
        private set

    init {
        client.steerCenter = steerCenter
        sound.enabled = soundEnabled
        // Start listening for the car's Wi-Fi network immediately.
        wifi.request(onAvailable = { /* available; connect() will bind on demand */ })
        // Drive audio off the real connection state so cues fire for auto-reconnects
        // and link drops too, not just user taps.
        viewModelScope.launch {
            client.status
                .map { it.connection == ConnectionState.CONNECTED }
                .collectConnectionEdges()
        }
    }

    /** Emit connect/disconnect audio only on transitions, not every emission. */
    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.collectConnectionEdges() {
        var was = false
        collect { isConnected ->
            if (isConnected && !was) {
                sound.connect()
                sound.startEngine()
            } else if (!isConnected && was) {
                sound.disconnect()
                sound.stopEngine()
            }
            was = isConnected
        }
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
        if (upperLedOn) sound.ledOn() else sound.ledOff()
    }

    fun toggleLowerLed() {
        lowerLedOn = !lowerLedOn
        client.lowerLed(lowerLedOn)
        if (lowerLedOn) sound.ledOn() else sound.ledOff()
    }

    fun toggleSound() {
        soundEnabled = !soundEnabled
        sound.enabled = soundEnabled
        // Resume the idle drone immediately if we un-muted while connected.
        if (soundEnabled && client.status.value.connection == ConnectionState.CONNECTED) {
            sound.startEngine()
        }
        prefs.edit().putBoolean(KEY_SOUND, soundEnabled).apply()
    }

    /** UI hooks for the capture buttons so photo/record share the audio layer. */
    fun playPhotoCue() = sound.photo()
    fun playRecordCue() = sound.record()

    fun selectControllerProfile(profile: ControllerProfile) {
        controllerProfile = profile
        prefs.edit().putString(KEY_PROFILE, profile.name).apply()
    }

    /** Dispatch a discrete gamepad action (button or trigger press). */
    fun onGamepadAction(action: GamepadAction) = when (action) {
        GamepadAction.TOGGLE_UPPER_LED -> toggleUpperLed()
        GamepadAction.TOGGLE_LOWER_LED -> toggleLowerLed()
        GamepadAction.TOGGLE_CAMERA -> toggleCamera()
        GamepadAction.TOGGLE_CONNECTION -> toggleConnection()
    }

    /**
     * Apply a gamepad drive intent. Inputs are normalized:
     *   [steer] in -1..1 (left..right), [forward]/[reverse] in 0..1.
     * Converts to protocol units, honoring the current steering trim.
     */
    fun onGamepadDrive(steer: Float, forward: Float, reverse: Float) {
        val s = if (abs(steer) < STEER_DEADZONE) 0f else steer
        pushDrive(s, forward, reverse)
    }

    /**
     * Apply a touch-pad drive intent: [steer] and [throttle] in -1..1
     * (throttle positive = forward). No deadzone — touch input is deliberate.
     */
    fun onTouchDrive(steer: Float, throttle: Float) {
        val forward = if (throttle > 0f) throttle else 0f
        val reverse = if (throttle < 0f) -throttle else 0f
        pushDrive(steer, forward, reverse)
    }

    /** Immediately command neutral (used when the app backgrounds). Keeps the link. */
    fun stopDrive() {
        client.stop()
        sound.setEngineThrottle(0f)
    }

    /** Steering trim as a signed offset from center (negative = left). */
    val steerTrimOffset: Int get() = steerCenter - Protocol.STEER_CENTER_DEFAULT

    fun trimLeft() { applyTrim(steerCenter - TRIM_STEP); sound.trimDown() }
    fun trimRight() { applyTrim(steerCenter + TRIM_STEP); sound.trimUp() }
    fun trimReset() { applyTrim(Protocol.STEER_CENTER_DEFAULT); sound.trimDown() }

    private fun applyTrim(value: Int) {
        steerCenter = value.coerceIn(TRIM_MIN, TRIM_MAX)
        client.steerCenter = steerCenter
        client.stop() // recenter the wheels to the new trim immediately
        prefs.edit().putInt(KEY_TRIM, steerCenter).apply()
    }

    /**
     * Best-effort current Wi-Fi SSID for connectivity guidance, or null if it
     * can't be read (permissions / OS restrictions on newer Android).
     */
    fun currentWifiSsid(): String? {
        val wm = getApplication<Application>()
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val raw = wm.connectionInfo?.ssid?.trim('"') ?: return null
        return if (raw.isBlank() || raw.contains("unknown", ignoreCase = true)) null else raw
    }

    fun isCarNetwork(ssid: String?): Boolean = ssid?.contains(Protocol.SSID_PREFIX) == true

    /** Current Wi-Fi RSSI in dBm for the signal meter, or null if unavailable. */
    fun currentRssi(): Int? {
        val wm = getApplication<Application>()
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val rssi = wm.connectionInfo?.rssi ?: return null
        return if (rssi == 0 || rssi <= -100 || rssi == -127) null else rssi
    }

    private fun pushDrive(steer: Float, forward: Float, reverse: Float) {
        val steerLevel = client.steerCenter + (steer.coerceIn(-1f, 1f) * (Protocol.STEER_TOTAL_STEP / 2)).roundToInt()
        val net = forward - reverse
        val fw = if (net > 0f) (expo(net) * Protocol.SPEED_TOTAL_STEP).roundToInt() else 0
        val bw = if (net < 0f) (expo(-net) * Protocol.SPEED_TOTAL_STEP).roundToInt() else 0
        client.setDrive(fw, bw, steerLevel)
        sound.setEngineThrottle(abs(net))
    }

    /**
     * Throttle response curve. Maps a 0..1 request through an exponential so the
     * first third of travel is gentle (fine control near a stop) while full
     * deflection still reaches full speed — this is a *curve*, not a limiter.
     */
    private fun expo(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c.toDouble().pow(THROTTLE_EXPO.toDouble()).toFloat()
    }

    override fun onCleared() {
        client.shutdown()
        wifi.release()
        sound.release()
    }

    private companion object {
        const val STEER_DEADZONE = 0.12f
        const val THROTTLE_EXPO = 1.7f
        const val PREFS = "nomad_prefs"
        const val KEY_PROFILE = "controller_profile"
        const val KEY_TRIM = "steer_center"
        const val KEY_SOUND = "sound_enabled"
        const val TRIM_STEP = 2
        const val TRIM_MIN = Protocol.STEER_CENTER_DEFAULT - 30 // 98
        const val TRIM_MAX = Protocol.STEER_CENTER_DEFAULT + 30 // 158
    }
}
