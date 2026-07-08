package us.creativeworks.nomad.input

import android.view.KeyEvent
import android.view.MotionEvent
import us.creativeworks.nomad.ui.ControlViewModel
import kotlin.math.abs

/**
 * Translates Android gamepad input into car control, routed through the
 * [ControlViewModel] so it stays in sync with the touchscreen UI.
 *
 * Mapping (standard Android gamepad layout):
 *   - Left stick X .............. steering
 *   - Right trigger (RT) ........ forward throttle
 *   - Left trigger (LT) ......... reverse throttle
 *   - Left stick Y (fallback) ... throttle, if the controller has no analog
 *                                 triggers (they read ~0)
 *   - A ......................... toggle upper LED
 *   - B ......................... toggle lower LED
 *   - X ......................... toggle camera
 *   - Y / Start ................. connect / disconnect
 *
 * Analog sticks arrive as [MotionEvent] joystick axes; buttons as [KeyEvent].
 * The host Activity forwards both here from its dispatch overrides.
 */
class GamepadController(private val vm: ControlViewModel) {

    /** Handle a joystick MotionEvent. Returns true if consumed. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromJoystick()) return false

        val steer = deadzone(event.getAxisValue(MotionEvent.AXIS_X))

        var forward = trigger(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        var reverse = trigger(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)

        // Fallback for pads without analog triggers: left stick Y (up = forward).
        if (forward < TRIGGER_FLOOR && reverse < TRIGGER_FLOOR) {
            val y = -deadzone(event.getAxisValue(MotionEvent.AXIS_Y))
            if (y > 0f) forward = y else reverse = -y
        }

        vm.onGamepadDrive(steer, forward.coerceIn(0f, 1f), reverse.coerceIn(0f, 1f))
        return true
    }

    /** Handle a gamepad button KeyEvent. Returns true if consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode !in HANDLED_BUTTONS) return false
        // Act once on press; still consume the up/repeat so buttons like B don't
        // trigger default behaviors (e.g. Back) on some controllers.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> vm.toggleUpperLed()
                KeyEvent.KEYCODE_BUTTON_B -> vm.toggleLowerLed()
                KeyEvent.KEYCODE_BUTTON_X -> vm.toggleCamera()
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START -> vm.toggleConnection()
            }
        }
        return true
    }

    private fun trigger(event: MotionEvent, primary: Int, fallback: Int): Float {
        val v = event.getAxisValue(primary)
        return if (v != 0f) v else event.getAxisValue(fallback)
    }

    private fun deadzone(v: Float): Float = if (abs(v) < STICK_FLAT) 0f else v

    private fun MotionEvent.isFromJoystick(): Boolean =
        source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK &&
            action == MotionEvent.ACTION_MOVE

    private companion object {
        const val STICK_FLAT = 0.12f
        const val TRIGGER_FLOOR = 0.05f
        val HANDLED_BUTTONS = setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START,
        )
    }
}
