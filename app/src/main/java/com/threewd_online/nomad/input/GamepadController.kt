package com.threewd_online.nomad.input

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.threewd_online.nomad.ui.ControlViewModel

/**
 * Translates Android gamepad input into car control, routed through the
 * [ControlViewModel] so it stays in sync with the touchscreen UI.
 *
 * The concrete mapping comes from the active [ControllerProfile] (read from the
 * ViewModel each event). This class only handles the mechanics common to all
 * profiles: reading events, edge-detecting triggers used as buttons, and
 * consuming handled keys.
 */
class GamepadController(private val vm: ControlViewModel) {

    // Edge-detection state for triggers-used-as-buttons (with hysteresis).
    private var activeProfile: ControllerProfile? = null
    private var leftTriggerDown = false
    private var rightTriggerDown = false

    /** Handle a joystick MotionEvent. Returns true if consumed. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromJoystick()) return false
        val profile = vm.controllerProfile

        // Reset trigger edge state when the profile changes under us.
        if (profile != activeProfile) {
            activeProfile = profile
            leftTriggerDown = false
            rightTriggerDown = false
        }

        val intent = profile.drive(event)
        vm.onGamepadDrive(intent.steer, intent.forward, intent.reverse)

        // Triggers acting as on/off buttons (only when the profile says so).
        profile.leftTriggerAction?.let { action ->
            val v = triggerValue(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
            leftTriggerDown = edge(v, leftTriggerDown) { vm.onGamepadAction(action) }
        }
        profile.rightTriggerAction?.let { action ->
            val v = triggerValue(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            rightTriggerDown = edge(v, rightTriggerDown) { vm.onGamepadAction(action) }
        }
        return true
    }

    /** Handle a gamepad button KeyEvent. Returns true if consumed. */
    fun onKey(event: KeyEvent): Boolean {
        val action = vm.controllerProfile.buttonAction(event.keyCode)
        if (action == null) {
            // Log unmapped gamepad buttons so a controller's exact keycodes can be
            // identified (e.g. which code the Stadia "Menu" button sends).
            if (event.action == KeyEvent.ACTION_DOWN && event.isFromGamepad()) {
                Log.d(TAG, "Unmapped gamepad button: keyCode=${event.keyCode} " +
                    "(${KeyEvent.keyCodeToString(event.keyCode)})")
            }
            return false
        }
        // Fire once on press; still consume up/repeat so mapped buttons don't
        // trigger default behaviors (e.g. B acting as Back) on some controllers.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            vm.onGamepadAction(action)
        }
        return true
    }

    /** Rising-edge detector with hysteresis; runs [onPress] once per press. */
    private inline fun edge(value: Float, wasDown: Boolean, onPress: () -> Unit): Boolean {
        val threshold = if (wasDown) TRIGGER_RELEASE else TRIGGER_PRESS
        val isDown = value > threshold
        if (isDown && !wasDown) onPress()
        return isDown
    }

    private fun triggerValue(event: MotionEvent, primary: Int, fallback: Int): Float {
        val v = event.getAxisValue(primary)
        return if (v != 0f) v else event.getAxisValue(fallback)
    }

    private fun MotionEvent.isFromJoystick(): Boolean =
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            action == MotionEvent.ACTION_MOVE

    private fun KeyEvent.isFromGamepad(): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            KeyEvent.isGamepadButton(keyCode)

    private companion object {
        const val TAG = "GamepadController"
        const val TRIGGER_PRESS = 0.6f
        const val TRIGGER_RELEASE = 0.4f
    }
}
